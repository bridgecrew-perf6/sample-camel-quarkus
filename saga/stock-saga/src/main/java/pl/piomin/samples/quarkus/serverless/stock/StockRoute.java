package pl.piomin.samples.quarkus.serverless.stock;

import com.github.piomin.entity.model.customer.Customer;
import com.github.piomin.entity.model.product.Product;
import lombok.Data;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jackson.JacksonDataFormat;
import org.apache.camel.model.rest.RestBindingMode;

import javax.enterprise.context.ApplicationScoped;
import java.io.Serializable;
import java.util.List;
import java.util.Random;

// camel-k: trait=knative-service.enabled=true
// camel-k: dependency=mvn:org.apache.camel.quarkus:camel-quarkus-core
// camel-k: dependency=mvn:org.apache.camel.quarkus:camel-quarkus-jpa
// camel-k: dependency=mvn:org.apache.camel.quarkus:camel-quarkus-jackson
// camel-k: dependency=mvn:io.quarkus:quarkus-jdbc-postgresql
// camel-k: dependency=mvn:org.projectlombok:lombok:1.18.22
// camel-k: dependency=github:piomin/entity-model/1.1

@ApplicationScoped
public class StockRoute extends RouteBuilder {

    private static long i = 0;
    private final JacksonDataFormat FORMAT = new JacksonDataFormat(Order.class);

    @Override
    public void configure() throws Exception {
        restConfiguration().bindingMode(RestBindingMode.json);

        rest("/stock")
                .post("/reserve").consumes("application/json").type(Order.class)
                .route()
                    .log("Order received: ${body}")
                    .choice()
                        .when().simple("${body.status.toString()} == 'NEW'")
                            .setBody(exchange -> {
                                Order order = exchange.getIn().getBody(Order.class);
                                order.setStatus(OrderStatus.IN_PROGRESS);
                                return order;
                            })
                            .marshal(FORMAT)
                            .log("Reservation sent: ${body}")
                            .toD("kafka:reserve-events?brokers=${env.KAFKA_BOOTSTRAP_SERVERS}")
                    .end()
                    .unmarshal(FORMAT)
                    .setProperty("orderItems", simple("${body.productCount}", Integer.class))
                    .setProperty("orderStatus", simple("${body.status}", OrderStatus.class))
                    .toD("jpa:" + Product.class.getName() + "?query=select p from Product p where p.id = ${body.productId}")
                    .choice()
                        .when().simple("${exchangeProperty.orderStatus} == 'IN_PROGRESS'")
                            .setBody(exchange -> {
                                Product product = (Product) exchange.getIn().getBody(List.class).get(0);
                                product.setItemsReserved(product.getItemsReserved() + exchange.getProperty("orderItems", Integer.class));
                                product.setItemsAvailable(product.getItemsAvailable() - exchange.getProperty("orderItems", Integer.class));
                                return product;
                            }).endChoice()
                        .otherwise()
                            .setBody(exchange -> {
                                Product product = (Product) exchange.getIn().getBody(List.class).get(0);
                                product.setItemsReserved(product.getItemsReserved() - exchange.getProperty("orderItems", Integer.class));
                                return product;
                            })
                    .end()
                    .log("Current product: ${body}")
                    .to("jpa:" + Product.class.getName() + "?useExecuteUpdate=true")
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(201))
                    .setBody(constant(null))
                .end()
        .endRest();

        Random r = new Random();
        from("timer://runOnce?repeatCount=1&delay=100")
                .loop(10)
                .setBody(exchange -> new Product(null, "Test"+(++i), r.nextInt(10000), 0))
                .to("jpa:" + Product.class.getName())
                .log("Add: ${body}")
                .end();
    }
}

@Data
class Order implements Serializable {
    private Long id;
    private Long customerId;
    private int amount;
    private OrderStatus status;
    private Long productId;
    private int productCount;
}

enum OrderStatus {
    NEW, REJECTED, CONFIRMED, IN_PROGRESS;
}