# Spanner Node.js Benchmark

This directory contains the Node.js implementation of the Spanner client benchmark.

## Benchmark Description
The benchmark performs a point query selecting one row based on a randomly selected primary key value using a query parameter.

## Prerequisites
- Node.js 18 or later
- npm

## Running Locally
You can run the benchmark locally:

```bash
npm install
node index.js <PROJECT_ID> <INSTANCE_ID> <DATABASE_ID> <TABLE_NAME>
```

## Docker
To build the Docker image:

```bash
docker build -t spanner-node-benchmark .
```
