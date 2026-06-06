#!/usr/bin/env python3
import os
import sys
import json
import urllib.request
import re

# Strict enum definitions for sanitization
SUPPORTED_CLIENTS = {"go", "java", "node", "python", "rust"}
SUPPORTED_BENCHMARKS = {"point-select", "select-update", "read-large-result-set", "tpcc", "tpcc-init"}

# Regex patterns for strict sanitization
BRANCH_PATTERN = re.compile(r"^[a-zA-Z0-9/._-]+$")
DURATION_PATTERN = re.compile(r"^[0-9]+[smh]$")
CPU_PATTERN = re.compile(r"^[1-9][0-9]*$")
MEMORY_PATTERN = re.compile(r"^[1-9][0-9]*(Gi|Mi)$")
NAME_PATTERN = re.compile(r"^[a-zA-Z0-9/._-]+$")
FLOAT_PATTERN = re.compile(r"^[0-9]+(\.[0-9]+)?$")
INT_PATTERN = re.compile(r"^[0-9]+$")

LOAD_TYPE_SUPPORTED = {"steady", "spiky", "gradual"}

SYSTEM_INSTRUCTION = (
    "You are a structured parser that translates natural language requests to deploy Spanner client benchmarks "
    "into a structured JSON payload.\n\n"
    "The user wants to deploy one or more benchmarks. If they ask to compare two branches (e.g., 'branch-a vs main' "
    "or 'my-branch and main'), output two separate run objects: one for each branch, with all other properties "
    "kept identical.\n\n"
    "If the request is ambiguous, lacks critical information (such as which language client to run), is not related "
    "to deploying benchmarks, or is ridiculous/nonsense (e.g., 'run a benchmark that tells me how my feature performs'), "
    "do NOT output any items in the 'runs' list. Instead, set the 'error' field to a descriptive error message "
    "explaining what is missing or why the request is invalid.\n\n"
    "Defaults:\n"
    "- duration: 60m\n"
    "- cpu: 2\n"
    "- memory: 1Gi\n"
    "- benchmark_type: point-select (unless another type like tpcc or read-large-result-set is specified)\n"
    "- benchmark_name: a short, descriptive name using the client type and branch name (e.g., node-main-opt)\n"
)

RESPONSE_SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "error": {
            "type": "STRING",
            "description": "Explanatory error message if the input is ambiguous, nonsense, or cannot be parsed."
        },
        "runs": {
            "type": "ARRAY",
            "items": {
                "type": "OBJECT",
                "properties": {
                    "client_type": {
                        "type": "STRING",
                        "enum": list(SUPPORTED_CLIENTS)
                    },
                    "client_branch": {
                        "type": "STRING"
                    },
                    "benchmark_type": {
                        "type": "STRING",
                        "enum": list(SUPPORTED_BENCHMARKS)
                    },
                    "duration": {
                        "type": "STRING"
                    },
                    "cpu": {
                        "type": "STRING"
                    },
                    "memory": {
                        "type": "STRING"
                    },
                    "benchmark_name": {
                        "type": "STRING"
                    },
                    "load_type": {
                        "type": "STRING",
                        "enum": list(LOAD_TYPE_SUPPORTED)
                    },
                    "tps": {
                        "type": "STRING"
                    },
                    "threads": {
                        "type": "STRING"
                    },
                    "num_rows": {
                        "type": "STRING"
                    },
                    "burst_factor": {
                        "type": "STRING"
                    },
                    "burst_duration": {
                        "type": "STRING"
                    },
                    "burst_fraction": {
                        "type": "STRING"
                    },
                    "warehouses": {
                        "type": "STRING"
                    },
                    "items": {
                        "type": "STRING"
                    },
                    "clients": {
                        "type": "STRING"
                    }
                },
                "required": ["client_type", "client_branch", "benchmark_type"]
            }
        }
    }
}

def sanitize_value(val, pattern, default_val=None):
    if val is None:
        return default_val
    val_str = str(val).strip()
    if not pattern.match(val_str):
        raise ValueError(f"Value '{val_str}' does not match safe pattern: {pattern.pattern}")
    return val_str

def sanitize_run(run):
    client = run.get("client_type")
    if client not in SUPPORTED_CLIENTS:
        raise ValueError(f"Unsupported client: {client}")
        
    bench = run.get("benchmark_type")
    if bench not in SUPPORTED_BENCHMARKS:
        raise ValueError(f"Unsupported benchmark type: {bench}")

    # Apply strict regex checks to prevent command injection or bad inputs
    branch = sanitize_value(run.get("client_branch"), BRANCH_PATTERN)
    duration = sanitize_value(run.get("duration"), DURATION_PATTERN, "60m")
    cpu = sanitize_value(run.get("cpu"), CPU_PATTERN, "2")
    memory = sanitize_value(run.get("memory"), MEMORY_PATTERN, "1Gi")
    name = sanitize_value(run.get("benchmark_name"), NAME_PATTERN, f"{client}-{branch}")

    load_type = run.get("load_type")
    if load_type and load_type not in LOAD_TYPE_SUPPORTED:
        raise ValueError(f"Unsupported load_type: {load_type}")
    load_type = load_type or ""

    for_alerting = "false"

    tps = sanitize_value(run.get("tps"), FLOAT_PATTERN, "")
    threads = sanitize_value(run.get("threads"), INT_PATTERN, "")
    num_rows = sanitize_value(run.get("num_rows"), INT_PATTERN, "")
    burst_factor = sanitize_value(run.get("burst_factor"), FLOAT_PATTERN, "")
    burst_duration = sanitize_value(run.get("burst_duration"), FLOAT_PATTERN, "")
    burst_fraction = sanitize_value(run.get("burst_fraction"), FLOAT_PATTERN, "")
    warehouses = sanitize_value(run.get("warehouses"), INT_PATTERN, "")
    items = sanitize_value(run.get("items"), INT_PATTERN, "")
    clients = sanitize_value(run.get("clients"), INT_PATTERN, "")

    return {
        "client_type": client,
        "client_branch": branch,
        "benchmark_type": bench,
        "duration": duration,
        "cpu": cpu,
        "memory": memory,
        "benchmark_name": name,
        "load_type": load_type,
        "tps": tps,
        "threads": threads,
        "num_rows": num_rows,
        "burst_factor": burst_factor,
        "burst_duration": burst_duration,
        "burst_fraction": burst_fraction,
        "warehouses": warehouses,
        "items": items,
        "clients": clients,
        "for_alerting": for_alerting
    }

def main():
    if len(sys.argv) < 2:
        print("Error: Missing instruction argument.", file=sys.stderr)
        sys.exit(1)

    instruction = sys.argv[1]

    access_token = os.environ.get("GCP_ACCESS_TOKEN")
    project_id = os.environ.get("PROJECT_ID", "appdev-soda-spanner-staging")

    if access_token:
        # Vertex AI endpoint format
        region = "us-central1"
        url = f"https://{region}-aiplatform.googleapis.com/v1/projects/{project_id}/locations/{region}/publishers/google/models/gemini-2.5-flash:generateContent"
        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {access_token}"
        }
    else:
        # Fallback to standard Google AI Studio (developer API key)
        api_key = os.environ.get("GEMINI_API_KEY")
        if not api_key:
            print("Error: Either GCP_ACCESS_TOKEN or GEMINI_API_KEY must be set.", file=sys.stderr)
            sys.exit(1)
        url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key={api_key}"
        headers = {
            "Content-Type": "application/json"
        }

    # Read README.md context if it exists
    readme_content = ""
    readme_path = "README.md"
    if os.path.exists(readme_path):
        try:
            with open(readme_path, "r", encoding="utf-8") as f:
                readme_content = f.read()
        except Exception as e:
            print(f"Warning: Could not read README.md: {e}", file=sys.stderr)

    prompt = f"System Instructions:\n{SYSTEM_INSTRUCTION}\n\n"
    if readme_content:
        prompt += f"Reference Documentation:\n{readme_content}\n\n"
    prompt += f"User Request: {instruction}"
    
    payload = {
        "contents": [
            {
                "role": "user",
                "parts": [
                    {
                        "text": prompt
                    }
                ]
            }
        ],
        "generationConfig": {
            "responseMimeType": "application/json",
            "responseSchema": RESPONSE_SCHEMA
        }
    }

    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers=headers,
        method="POST"
    )

    try:
        with urllib.request.urlopen(req) as response:
            res_body = response.read().decode("utf-8")
            res_data = json.loads(res_body)
            
            # Extract candidate text
            text_response = res_data["candidates"][0]["content"]["parts"][0]["text"]
            parsed_response = json.loads(text_response)
            
            # Check for logical error returned by LLM
            err_msg = parsed_response.get("error")
            if err_msg:
                print(f"Workflow Request Error: {err_msg}", file=sys.stderr)
                sys.exit(1)
            
            runs = parsed_response.get("runs", [])
            if not runs:
                print("Workflow Request Error: The request was too ambiguous or did not specify a valid client/benchmark type. Please refine your instruction.", file=sys.stderr)
                sys.exit(1)
            
            # Validate and sanitize all runs
            sanitized_runs = []
            for r in runs:
                sanitized_runs.append(sanitize_run(r))
                
            # Print output as formatted JSON
            print(json.dumps({"runs": sanitized_runs}, indent=2))

    except urllib.error.HTTPError as e:
        print(f"API HTTP Error: {e.code} - {e.read().decode('utf-8')}", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"Error: {str(e)}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()
