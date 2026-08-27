from __future__ import annotations

import json
import mimetypes
import os
import re
import smtplib
import ssl
from dataclasses import dataclass
from email.message import EmailMessage
from email.utils import formataddr, make_msgid
from pathlib import Path
from typing import Any

from mcp.server.fastmcp import FastMCP


SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_CONFIG_FILE = SCRIPT_DIR / "email_mcp_config.json"
DEFAULT_TIMEOUT_SECONDS = 20

mcp = FastMCP(
    "email-mailer",
    instructions=(
        "Tools for sending emails through a configured SMTP server. "
        "Use get_mail_config before sending if you need to confirm settings."
    ),
)


@dataclass
class MailConfig:
    smtp_host: str
    smtp_port: int
    smtp_username: str
    smtp_password: str
    from_email: str
    from_name: str
    use_tls: bool
    use_ssl: bool
    timeout_seconds: int


def _load_config_file() -> dict[str, Any]:
    config_path = Path(os.environ.get("EMAIL_MCP_CONFIG") or DEFAULT_CONFIG_FILE)
    if not config_path.exists():
        return {}
    try:
        return json.loads(config_path.read_text(encoding="utf-8"))
    except Exception as exc:
        raise RuntimeError(f"Failed to read email MCP config: {config_path}") from exc


def _env_or_file(name: str, config: dict[str, Any], default: Any = "") -> Any:
    value = os.environ.get(name)
    if value is not None and value != "":
        return value
    for key in _config_keys(name):
        if key in config and config[key] not in (None, ""):
            return config[key]
    return default


def _config_keys(name: str) -> list[str]:
    key = name.lower()
    keys = [key]
    if key.startswith("email_smtp_"):
        smtp_key = key.removeprefix("email_")
        keys.append(smtp_key)
        keys.append(smtp_key.removeprefix("smtp_"))
    elif key.startswith("email_"):
        keys.append(key.removeprefix("email_"))
    return keys


def _parse_bool(value: Any, default: bool = False) -> bool:
    if value is None or value == "":
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"1", "true", "yes", "y", "on"}


def _parse_int(value: Any, default: int) -> int:
    if value is None or value == "":
        return default
    try:
        return int(value)
    except Exception:
        return default


def load_config() -> MailConfig:
    file_config = _load_config_file()
    smtp_host = str(_env_or_file("EMAIL_SMTP_HOST", file_config)).strip()
    if not smtp_host:
        raise RuntimeError("EMAIL_SMTP_HOST is required.")

    smtp_username = str(_env_or_file("EMAIL_SMTP_USERNAME", file_config)).strip()
    smtp_password = str(_env_or_file("EMAIL_SMTP_PASSWORD", file_config)).strip()
    from_email = str(
        _env_or_file("EMAIL_SMTP_FROM_EMAIL", file_config, smtp_username)
    ).strip() or smtp_username
    from_name = str(_env_or_file("EMAIL_SMTP_FROM_NAME", file_config, "Spring AI Mailer")).strip()

    use_tls = _parse_bool(_env_or_file("EMAIL_SMTP_USE_TLS", file_config, True), True)
    use_ssl = _parse_bool(_env_or_file("EMAIL_SMTP_USE_SSL", file_config, False), False)
    timeout_seconds = _parse_int(
        _env_or_file("EMAIL_SMTP_TIMEOUT", file_config, DEFAULT_TIMEOUT_SECONDS),
        DEFAULT_TIMEOUT_SECONDS,
    )
    smtp_port_default = 465 if use_ssl else 587 if use_tls else 25
    smtp_port = _parse_int(_env_or_file("EMAIL_SMTP_PORT", file_config, smtp_port_default), smtp_port_default)

    if not from_email:
        raise RuntimeError("EMAIL_SMTP_FROM_EMAIL or EMAIL_SMTP_USERNAME is required.")

    return MailConfig(
        smtp_host=smtp_host,
        smtp_port=smtp_port,
        smtp_username=smtp_username,
        smtp_password=smtp_password,
        from_email=from_email,
        from_name=from_name,
        use_tls=use_tls,
        use_ssl=use_ssl,
        timeout_seconds=timeout_seconds,
    )


def sanitize_config(config: MailConfig) -> dict[str, Any]:
    return {
        "smtp_host": config.smtp_host,
        "smtp_port": config.smtp_port,
        "smtp_username_set": bool(config.smtp_username),
        "from_email": config.from_email,
        "from_name": config.from_name,
        "use_tls": config.use_tls,
        "use_ssl": config.use_ssl,
        "timeout_seconds": config.timeout_seconds,
        "config_file": str(Path(os.environ.get("EMAIL_MCP_CONFIG") or DEFAULT_CONFIG_FILE)),
    }


def parse_recipients(value: str) -> list[str]:
    if not value:
        return []
    return [item.strip() for item in re.split(r"[,\n;]+", value) if item.strip()]


def add_attachment(message: EmailMessage, attachment_path: str) -> None:
    path = Path(attachment_path).expanduser()
    if not path.exists():
        raise FileNotFoundError(f"Attachment not found: {path}")

    mime_type, _ = mimetypes.guess_type(path.name)
    if mime_type:
        maintype, subtype = mime_type.split("/", 1)
    else:
        maintype, subtype = "application", "octet-stream"

    data = path.read_bytes()
    message.add_attachment(data, maintype=maintype, subtype=subtype, filename=path.name)


def build_message(
    config: MailConfig,
    to: str,
    subject: str,
    body: str,
    body_format: str = "text",
    cc: str = "",
    bcc: str = "",
    reply_to: str = "",
    attachments: str = "",
) -> tuple[EmailMessage, list[str]]:
    to_list = parse_recipients(to)
    cc_list = parse_recipients(cc)
    bcc_list = parse_recipients(bcc)
    attachment_list = parse_recipients(attachments)

    if not to_list:
        raise ValueError("At least one recipient is required.")

    body_format_normalized = body_format.strip().lower()
    if body_format_normalized not in {"text", "plain", "html"}:
        raise ValueError("body_format must be one of: text, plain, html")

    message = EmailMessage()
    message["Subject"] = subject
    message["From"] = formataddr((config.from_name, config.from_email)) if config.from_name else config.from_email
    message["To"] = ", ".join(to_list)
    if cc_list:
        message["Cc"] = ", ".join(cc_list)
    if reply_to.strip():
        message["Reply-To"] = reply_to.strip()
    message["Message-ID"] = make_msgid()

    if body_format_normalized == "html":
        message.set_content("This message contains HTML content.")
        message.add_alternative(body, subtype="html")
    else:
        message.set_content(body)

    for attachment in attachment_list:
        add_attachment(message, attachment)

    all_recipients = to_list + cc_list + bcc_list
    return message, all_recipients


def open_smtp_connection(config: MailConfig) -> smtplib.SMTP:
    context = ssl.create_default_context()
    if config.use_ssl:
        client = smtplib.SMTP_SSL(
            config.smtp_host,
            config.smtp_port,
            timeout=config.timeout_seconds,
            context=context,
        )
    else:
        client = smtplib.SMTP(config.smtp_host, config.smtp_port, timeout=config.timeout_seconds)
        client.ehlo()
        if config.use_tls:
            client.starttls(context=context)
            client.ehlo()

    if config.smtp_username:
        client.login(config.smtp_username, config.smtp_password)
    return client


@mcp.tool()
def get_mail_config() -> dict[str, Any]:
    """Return the active SMTP configuration without exposing secrets."""
    return sanitize_config(load_config())


@mcp.tool()
def test_smtp_connection() -> dict[str, Any]:
    """Check whether the SMTP server accepts a connection and authentication."""
    config = load_config()
    try:
        with open_smtp_connection(config) as client:
            code, response = client.noop()
            return {
                "ok": True,
                "smtp_host": config.smtp_host,
                "smtp_port": config.smtp_port,
                "noop_code": code,
                "noop_response": response.decode(errors="ignore") if isinstance(response, bytes) else str(response),
            }
    except Exception as exc:
        return {
            "ok": False,
            "error": str(exc),
        }


@mcp.tool()
def send_email(
    to: str,
    subject: str,
    body: str,
    body_format: str = "text",
    cc: str = "",
    bcc: str = "",
    reply_to: str = "",
    attachments: str = "",
) -> dict[str, Any]:
    """
    Send an email through the configured SMTP server.

    Multiple recipients and attachments can be separated by commas, semicolons, or newlines.
    """
    config = load_config()
    message, recipients = build_message(
        config=config,
        to=to,
        subject=subject,
        body=body,
        body_format=body_format,
        cc=cc,
        bcc=bcc,
        reply_to=reply_to,
        attachments=attachments,
    )

    with open_smtp_connection(config) as client:
        refused = client.send_message(message, from_addr=config.from_email, to_addrs=recipients)

    return {
        "ok": True,
        "from": config.from_email,
        "to": parse_recipients(to),
        "cc": parse_recipients(cc),
        "bcc": parse_recipients(bcc),
        "subject": subject,
        "body_format": body_format,
        "attachments": parse_recipients(attachments),
        "message_id": message["Message-ID"],
        "refused_recipients": refused,
    }


def main() -> None:
    mcp.run()


if __name__ == "__main__":
    main()
