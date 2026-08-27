# Email MCP Server

This folder contains a Python MCP server for sending email through SMTP.

> The official MCP Python SDK requires Python 3.10 or newer.

## Files

- `email_mcp_server.py`: MCP server entrypoint
- `email_mcp_config.example.json`: sample config file

## Configuration

The server reads config in this order:

1. Environment variables
2. `EMAIL_MCP_CONFIG` JSON file
3. `email_mcp_config.json` in the same directory

Required settings:

- `EMAIL_SMTP_HOST`
- `EMAIL_SMTP_USERNAME`
- `EMAIL_SMTP_PASSWORD`
- `EMAIL_SMTP_FROM_EMAIL` or `EMAIL_SMTP_USERNAME`

Optional settings:

- `EMAIL_SMTP_PORT`
- `EMAIL_SMTP_FROM_NAME`
- `EMAIL_SMTP_USE_TLS`
- `EMAIL_SMTP_USE_SSL`
- `EMAIL_SMTP_TIMEOUT`

Outlook SMTP example:

```text
EMAIL_SMTP_HOST=smtp.office365.com
EMAIL_SMTP_PORT=587
EMAIL_SMTP_USERNAME=SilverAddie0030@outlook.com
EMAIL_SMTP_PASSWORD=YOUR_OUTLOOK_APP_PASSWORD_OR_SMTP_PASSWORD
EMAIL_SMTP_FROM_EMAIL=SilverAddie0030@outlook.com
EMAIL_SMTP_USE_TLS=true
EMAIL_SMTP_USE_SSL=false
```

## Enable in Spring Boot

The default application config starts this MCP server with:

```text
D:\ProgramData\Anaconda3\envs\claw\python.exe
```

The `utools` HTTP MCP connection is kept in the optional `utools-mcp` profile so an invalid
`x-mcp-key` does not prevent the default app startup.

## Tools

- `get_mail_config`
- `test_smtp_connection`
- `send_email`

## Install

```bash
pip install "mcp[cli]"
```
