from typing import Optional

from google.api_core.client_options import ClientOptions
from google.auth.credentials import AnonymousCredentials
from google.cloud.spanner_v1 import AsyncClient


def create_spanner_client(project_id: str, host: Optional[str] = None) -> AsyncClient:
    """
    Configures and instantiates an AsyncClient object.
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
        if (
            "localhost:" in endpoint
            or "127.0.0.1:" in endpoint
            or endpoint.startswith("unix:")
        ):
            client_kwargs["credentials"] = AnonymousCredentials()

    if "client_options" not in client_kwargs:
        client_kwargs["client_options"] = ClientOptions()

    return AsyncClient(project=project_id, **client_kwargs)
