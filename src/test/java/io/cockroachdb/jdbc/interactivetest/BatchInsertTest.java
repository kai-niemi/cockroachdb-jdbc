package io.cockroachdb.jdbc.interactivetest;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.cockroachdb.jdbc.integrationtest.AbstractIntegrationTest;
import io.cockroachdb.jdbc.integrationtest.support.AsciiText;
import io.cockroachdb.jdbc.integrationtest.support.DatabaseFixture;
import io.cockroachdb.jdbc.integrationtest.support.JdbcTemplate;

@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@Order(1)
@DatabaseFixture(beforeTestScript = "/db/product-ddl.sql")
@Tag("interactive-test")
public class BatchInsertTest extends AbstractIntegrationTest {
    private static final int PRODUCTS_PER_BATCH_COUNT = 10_000;

    @Order(1)
    @ParameterizedTest
    @ValueSource(ints = {1 << 4, 1 << 5, 1 << 6, 1 << 7, 1 << 8, 1 << 9, 1 << 10, 1 << 11, 1 << 12})
    public void whenInsertingUsingBatchStatements_thenObserveTimes(int batchSize) throws Exception {
        List<Product> products = new ArrayList<>();

        logger.info("INSERT {} products using batches of {}", PRODUCTS_PER_BATCH_COUNT, batchSize);

        IntStream.rangeClosed(1, PRODUCTS_PER_BATCH_COUNT).forEach(value -> {
            Product product = new Product();
            product.setId(UUID.randomUUID());
            product.setVersion(0);
            product.setInventory(1);
            product.setPrice(BigDecimal.ONE);
            product.setSku(UUID.randomUUID().toString());
            product.setName("CockroachDB Unleashed 2nd Ed");
            products.add(product);
        });

        Stream<List<Product>> chunks = JdbcTemplate.chunkedStream(products.stream(), batchSize);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);

            final Instant startTime = Instant.now();
            final AtomicInteger n = new AtomicInteger();
            final int totalChunks = Math.round(PRODUCTS_PER_BATCH_COUNT * 1f / batchSize);

            chunks.forEach(chunk -> {
                System.out.printf("%s\n",
                        AsciiText.progressBar(totalChunks, n.incrementAndGet(), batchSize + ""));

                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO product (id,version,inventory,price,name,sku) "
                        + "values (?,?,?,?,?,?)")) {

                    chunk.forEach(product -> {
                        try {
                            ps.setObject(1, product.getId());
                            ps.setObject(2, product.getVersion());
                            ps.setObject(3, product.getInventory());
                            ps.setObject(4, product.getPrice());
                            ps.setObject(5, product.getName());
                            ps.setObject(6, product.getSku());

                            ps.addBatch();
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    ps.executeLargeBatch();
                } catch (SQLException ex) {
                    throw new RuntimeException(ex);
                }
            });

            logger.info("Completed in {}\n{}",
                    Duration.between(startTime, Instant.now()),
                    AsciiText.shrug());
        }
    }

    @Order(2)
    @ParameterizedTest
    @ValueSource(ints = {1 << 4, 1 << 5, 1 << 6, 1 << 7, 1 << 8, 1 << 9, 1 << 10, 1 << 11, 1 << 12})
    public void whenInsertingUsingFromStatements_thenObserveTimes(int batchSize) throws Exception {
        List<Product> products = new ArrayList<>();

        logger.info("INSERT .. FROM {} products using chunks of {}", PRODUCTS_PER_BATCH_COUNT, batchSize);

        IntStream.rangeClosed(1, PRODUCTS_PER_BATCH_COUNT).forEach(value -> {
            Product product = new Product();
            product.setId(UUID.randomUUID());
            product.setVersion(0);
            product.setInventory(1);
            product.setPrice(BigDecimal.ONE);
            product.setSku(UUID.randomUUID().toString());
            product.setName("CockroachDB Unleashed 2nd Ed");
            products.add(product);
        });

        Stream<List<Product>> chunks = JdbcTemplate.chunkedStream(products.stream(), batchSize);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);

            final Instant startTime = Instant.now();
            final AtomicInteger n = new AtomicInteger();
            final int totalChunks = Math.round(PRODUCTS_PER_BATCH_COUNT * 1f / batchSize);

            chunks.forEach(chunk -> {
                System.out.printf("%s\n",
                        AsciiText.progressBar(totalChunks, n.incrementAndGet(), batchSize + ""));

                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO product(id,version,inventory,price,name,sku)"
                        + " select"
                        + "  unnest(?) as id,"
                        + "  unnest(?) as version,"
                        + "  unnest(?) as inventory,"
                        + "  unnest(?) as price,"
                        + "  unnest(?) as name,"
                        + "  unnest(?) as sku"
                        + " ON CONFLICT (id,version) do nothing")) {
                    List<Integer> qty = new ArrayList<>();
                    List<Integer> version = new ArrayList<>();
                    List<BigDecimal> price = new ArrayList<>();
                    List<UUID> id = new ArrayList<>();
                    List<String> name = new ArrayList<>();
                    List<String> sku = new ArrayList<>();

                    chunk.forEach(product -> {
                        id.add(product.getId());
                        version.add(product.getVersion());
                        qty.add(product.getInventory());
                        price.add(product.getPrice());
                        name.add(product.getName());
                        sku.add(product.getSku());
                    });

                    ps.setArray(1, ps.getConnection().createArrayOf("UUID", id.toArray()));
                    ps.setArray(2, ps.getConnection().createArrayOf("INT", version.toArray()));
                    ps.setArray(3, ps.getConnection().createArrayOf("BIGINT", qty.toArray()));
                    ps.setArray(4, ps.getConnection().createArrayOf("DECIMAL", price.toArray()));
                    ps.setArray(5, ps.getConnection().createArrayOf("VARCHAR", name.toArray()));
                    ps.setArray(6, ps.getConnection().createArrayOf("VARCHAR", sku.toArray()));

                    ps.executeLargeUpdate();
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });

            logger.info("Completed in {}\n{}",
                    Duration.between(startTime, Instant.now()),
                    AsciiText.shrug());
        }
    }
}
