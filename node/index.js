const { Spanner } = require('@google-cloud/spanner');
const { MeterProvider, PeriodicExportingMetricReader } = require('@opentelemetry/sdk-metrics');
const { OTLPMetricExporter } = require('@opentelemetry/exporter-metrics-otlp-grpc');
const { Resource } = require('@opentelemetry/resources');
const { SemanticResourceAttributes } = require('@opentelemetry/semantic-conventions');
const metrics = require('@opentelemetry/api');

const METER_NAME = "spanner-benchmark";
const COUNTER_NAME = "query_count";
const LATENCY_NAME = "query_latency";

async function main() {
    const args = process.argv.slice(2);
    if (args.length < 4) {
        console.error("Usage: node index.js <PROJECT_ID> <INSTANCE_ID> <DATABASE_ID> <TABLE_NAME>");
        process.exit(1);
    }

    const [projectId, instanceId, databaseId, tableName] = args;

    // Initialize OpenTelemetry
    const exporter = new OTLPMetricExporter();
    const reader = new PeriodicExportingMetricReader({
        exporter: exporter,
        exportIntervalMillis: 60000,
    });
    const provider = new MeterProvider({
        resource: new Resource({
            [SemanticResourceAttributes.SERVICE_NAME]: 'spanner-node-benchmark',
            'cloud.project.id': projectId,
        }),
    });
    provider.addMetricReader(reader);
    metrics.metrics.setGlobalMeterProvider(provider);

    const meter = metrics.metrics.getMeter(METER_NAME);
    const queryCounter = meter.createCounter(COUNTER_NAME, {
        description: "Number of queries executed",
    });
    const latencyHistogram = meter.createHistogram(LATENCY_NAME, {
        description: "Query latency in milliseconds",
        unit: "ms",
    });

    // Initialize Spanner
    const spanner = new Spanner({ projectId: projectId });
    const instance = spanner.instance(instanceId);
    const database = instance.database(databaseId);

    console.log("Starting benchmark...");

    // Run benchmark loop
    while (true) {
        const randomId = Math.floor(Math.random() * 10000);
        const query = {
            sql: `SELECT * FROM ${tableName} WHERE id = @id`,
            params: {
                id: randomId,
            },
            types: {
                id: 'INT64',
            },
        };

        const startTime = process.hrtime.bigint();
        try {
            const [rows] = await database.run(query);
            for (const row of rows) {
                // Consume results to avoid DCE
                row.toJSON();
            }
        } catch (err) {
            console.error("Query error:", err);
        }
        const endTime = process.hrtime.bigint();
        const latencyNs = endTime - startTime;
        const latencyMs = Number(latencyNs) / 1000000;

        // Record metrics
        queryCounter.add(1);
        latencyHistogram.record(latencyMs);

        console.log(`Query executed in ${latencyMs.toFixed(2)} ms`);

        await new Promise(resolve => setTimeout(resolve, 1000));
    }
}

main().catch(console.error);
