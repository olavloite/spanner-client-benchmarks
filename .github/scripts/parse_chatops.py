#!/usr/bin/env python3
import os
import sys
import json
import urllib.request
import re
import ssl

# Bypass SSL check for local execution environment compatibility
try:
    ssl._create_default_https_context = ssl._create_unverified_context
except AttributeError:
    pass

# Strict enum definitions for sanitization
SUPPORTED_CLIENTS = {"go", "java", "node", "python", "rust"}
SUPPORTED_BENCHMARKS = {
    "point-select",
    "select-update",
    "read-large-result-set",
    "tpcc",
    "tpcc-init",
}

# Regex patterns for strict sanitization
BRANCH_PATTERN = re.compile(r"^[a-zA-Z0-9/._-]+$")
DURATION_PATTERN = re.compile(r"^[0-9]+[smh]$")
CPU_PATTERN = re.compile(r"^[1-9][0-9]*$")
MEMORY_PATTERN = re.compile(r"^[1-9][0-9]*(Gi|Mi)$")
NAME_PATTERN = re.compile(r"^[a-zA-Z0-9/._-]+$")
FLOAT_PATTERN = re.compile(r"^[0-9]+(\.[0-9]+)?$")
INT_PATTERN = re.compile(r"^[0-9]+$")
REPO_PATTERN = re.compile(r"^https://github\.com/[a-zA-Z0-9_-]+/[a-zA-Z0-9_.-]+(\.git)?$")

LOAD_TYPE_SUPPORTED = {"steady", "spiky", "gradual"}

RESPONSE_SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "clarification_needed": {
            "type": "BOOLEAN",
            "description": "True if user instructions are incomplete or ambiguous, and you need to ask a question.",
        },
        "question": {
            "type": "STRING",
            "description": "The clarification question to ask the user if clarification_needed is true.",
        },
        "runs": {
            "type": "ARRAY",
            "items": {
                "type": "OBJECT",
                "properties": {
                    "client_type": {"type": "STRING", "enum": list(SUPPORTED_CLIENTS)},
                    "client_branch": {"type": "STRING"},
                    "client_repo": {
                        "type": "STRING",
                        "description": "The GitHub repository URL of the fork to clone (optional)"
                    },
                    "benchmark_type": {
                        "type": "STRING",
                        "enum": list(SUPPORTED_BENCHMARKS),
                    },
                    "duration": {"type": "STRING"},
                    "cpu": {"type": "STRING"},
                    "memory": {"type": "STRING"},
                    "benchmark_name": {"type": "STRING"},
                    "load_type": {"type": "STRING", "enum": list(LOAD_TYPE_SUPPORTED)},
                    "tps": {"type": "STRING"},
                    "threads": {"type": "STRING"},
                    "num_rows": {"type": "STRING"},
                    "burst_factor": {"type": "STRING"},
                    "burst_duration": {"type": "STRING"},
                    "burst_fraction": {"type": "STRING"},
                    "warehouses": {"type": "STRING"},
                    "items": {"type": "STRING"},
                    "clients": {"type": "STRING"},
                },
                "required": ["client_type", "client_branch", "benchmark_type"],
            },
        },
    },
    "required": ["clarification_needed"],
}


def sanitize_value(val, pattern, default_val=None):
    if val is None:
        return default_val
    val_str = str(val).strip()
    if not val_str:
        return default_val
    if not pattern.match(val_str):
        raise ValueError(
            f"Value '{val_str}' does not match safe pattern: {pattern.pattern}"
        )
    return val_str


def sanitize_run(run):
    client = run.get("client_type")
    if client not in SUPPORTED_CLIENTS:
        raise ValueError(f"Unsupported client: {client}")

    bench = run.get("benchmark_type")
    if bench not in SUPPORTED_BENCHMARKS:
        raise ValueError(f"Unsupported benchmark type: {bench}")

    branch = sanitize_value(run.get("client_branch"), BRANCH_PATTERN)
    repo = sanitize_value(run.get("client_repo"), REPO_PATTERN, "")
    duration = sanitize_value(run.get("duration"), DURATION_PATTERN, "60m")
    cpu = sanitize_value(run.get("cpu"), CPU_PATTERN, "2")
    memory = sanitize_value(run.get("memory"), MEMORY_PATTERN, "1Gi")
    name = sanitize_value(run.get("benchmark_name"), NAME_PATTERN, f"{client}-{branch}")

    load_type = run.get("load_type")
    if load_type and load_type not in LOAD_TYPE_SUPPORTED:
        raise ValueError(f"Unsupported load_type: {load_type}")
    load_type = load_type or ""

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
        "client_repo": repo,
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
        "for_alerting": "false",
    }


def clean_comment(body):
    # Remove trigger prefix @gemini-bench to make parsing easier for LLM
    return re.sub(r"^@gemini-bench\s*", "", body, flags=re.IGNORECASE).strip()


def build_messages(thread_data):
    messages = []

    # Message 1: The issue description (User)
    initial_body = clean_comment(thread_data.get("body", ""))
    if initial_body:
        messages.append({"role": "user", "parts": [{"text": initial_body}]})

    # Comments: Multi-turn messages
    for comment in thread_data.get("comments", []):
        author = comment.get("author", {}).get("login", "")
        body = comment.get("body", "")

        # Check if bot comment
        is_bot = (
            "github-actions" in author or "[bot]" in author or body.startswith("🤖")
        )
        role = "model" if is_bot else "user"
        cleaned_body = clean_comment(body)

        if cleaned_body:
            if messages and messages[-1]["role"] == role:
                # Merge consecutive comments of the same role to maintain strict user/model alternation
                messages[-1]["parts"][0]["text"] += "\n\n" + cleaned_body
            else:
                messages.append({"role": role, "parts": [{"text": cleaned_body}]})

    return messages


def main():
    if len(sys.argv) < 2:
        print("Error: Missing thread.json argument.", file=sys.stderr)
        sys.exit(1)

    thread_path = sys.argv[1]
    if not os.path.exists(thread_path):
        print(f"Error: Thread file not found at {thread_path}", file=sys.stderr)
        sys.exit(1)

    try:
        with open(thread_path, "r", encoding="utf-8") as f:
            thread_data = json.load(f)
    except Exception as e:
        print(f"Error reading thread JSON: {e}", file=sys.stderr)
        sys.exit(1)

    access_token = os.environ.get("GCP_ACCESS_TOKEN")
    project_id = os.environ.get("PROJECT_ID", "appdev-soda-spanner-staging")

    if access_token:
        region = "us-central1"
        url = f"https://{region}-aiplatform.googleapis.com/v1/projects/{project_id}/locations/{region}/publishers/google/models/gemini-2.5-flash:generateContent"
        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {access_token}",
        }
    else:
        api_key = os.environ.get("GEMINI_API_KEY")
        if not api_key:
            print(
                "Error: Either GCP_ACCESS_TOKEN or GEMINI_API_KEY must be set.",
                file=sys.stderr,
            )
            sys.exit(1)
        url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key={api_key}"
        headers = {"Content-Type": "application/json"}

    # Read system instructions
    instructions_content = ""
    instructions_path = ".github/scripts/bot_instructions.md"
    if os.path.exists(instructions_path):
        try:
            with open(instructions_path, "r", encoding="utf-8") as f:
                instructions_content = f.read()
        except Exception as e:
            print(f"Warning: Could not read bot instructions: {e}", file=sys.stderr)

    system_instruction = instructions_content or "You are a benchmark parser assistant."

    # Dynamically inject supported properties to keep bot_instructions.md DRY
    system_instruction += (
        f"\n\n## Reference Configuration Options\n"
        f"*   Supported Client Types (client_type): {', '.join(sorted(SUPPORTED_CLIENTS))}\n"
        f"*   Supported Benchmark Types (benchmark_type): {', '.join(sorted(SUPPORTED_BENCHMARKS))}\n"
        f"*   Supported Load Types (load_type): {', '.join(sorted(LOAD_TYPE_SUPPORTED))}\n"
    )

    # Build the conversation history
    messages = build_messages(thread_data)
    if not messages:
        print("Error: Conversation history is empty.", file=sys.stderr)
        sys.exit(1)

    payload = {
        "contents": messages,
        "systemInstruction": {"parts": [{"text": system_instruction}]},
        "generationConfig": {"responseMimeType": "application/json"},
    }

    # Debug: print the constructed payload to stderr
    print("API Payload:\n" + json.dumps(payload, indent=2), file=sys.stderr)

    req = urllib.request.Request(
        url, data=json.dumps(payload).encode("utf-8"), headers=headers, method="POST"
    )

    try:
        print("Sending API request to Gemini...", file=sys.stderr)
        with urllib.request.urlopen(req, timeout=30) as response:
            print("Response received, parsing...", file=sys.stderr)
            res_body = response.read().decode("utf-8")
            res_data = json.loads(res_body)

            text_response = res_data["candidates"][0]["content"]["parts"][0]["text"]
            try:
                parsed_response = json.loads(text_response)
            except json.JSONDecodeError as jde:
                print(f"Error parsing Gemini response as JSON: {jde}", file=sys.stderr)
                print(f"Raw Gemini response was:\n{text_response}", file=sys.stderr)
                sys.exit(1)

            # Case A: Clarification Needed
            if parsed_response.get("clarification_needed"):
                question = parsed_response.get("question")
                if not question:
                    question = "Please clarify your request."
                print(
                    json.dumps(
                        {"clarification_needed": True, "question": question}, indent=2
                    )
                )
                sys.exit(0)

            # Case B: Valid runs list
            runs = parsed_response.get("runs", [])
            if not runs:
                print(
                    json.dumps(
                        {
                            "clarification_needed": True,
                            "question": "The instructions did not specify any benchmark runs. What client and branch would you like to deploy?",
                        },
                        indent=2,
                    )
                )
                sys.exit(0)

            sanitized_runs = []
            for r in runs:
                sanitized_runs.append(sanitize_run(r))

            print(
                json.dumps(
                    {"clarification_needed": False, "runs": sanitized_runs}, indent=2
                )
            )

    except urllib.error.HTTPError as e:
        print(f"API HTTP Error: {e.code} - {e.read().decode('utf-8')}", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"Error: {str(e)}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
