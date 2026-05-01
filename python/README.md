# Spanner Python Benchmark

This directory contains the Python implementation of the Spanner client benchmark.

## Benchmark Description
The benchmark performs a point query selecting one row based on a randomly selected primary key value using a query parameter.

## Prerequisites
- Python 3.10 or later
- pip

## Running Locally
You can run the benchmark locally:

```bash
pip install -r requirements.txt
python main.py <PROJECT_ID> <INSTANCE_ID> <DATABASE_ID> <TABLE_NAME>
```

## Docker
To build the Docker image:

```bash
docker build -t spanner-python-benchmark .
```
