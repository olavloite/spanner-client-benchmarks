from typing import Optional

from google.api_core.client_options import ClientOptions
from google.auth.credentials import AnonymousCredentials
from google.cloud import spanner


def create_spanner_client(
    project_id: str, host: Optional[str] = None
) -> spanner.Client:
    """
    Configures and instantiates a standard google-cloud-spanner Client object.
    """
    client_kwargs = {}

    if host:
        endpoint = host
        if endpoint.startswith("http://"):
            endpoint = endpoint[7:]
        elif endpoint.startswith("https://"):
            endpoint = endpoint[8:]

        client_kwargs["client_options"] = ClientOptions(api_endpoint=endpoint)

        # If talking to an emulator via localhost/127.0.0.1 but SPANNER_EMULATOR_HOST env wasn't set,
        # assign AnonymousCredentials to disable active IAM token exchange.
        if "localhost:" in endpoint or "127.0.0.1:" in endpoint:
            client_kwargs["credentials"] = AnonymousCredentials()

    return spanner.Client(project=project_id, **client_kwargs)
