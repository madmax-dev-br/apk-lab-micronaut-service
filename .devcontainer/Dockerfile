# The devcontainers Java image ships an apt source for Yarn whose signing key expired; any feature that runs apt-get update (the SSH server) fails on it,
# and Codespaces then falls back to a recovery container without Java or Node. Yarn is not used here: the source is removed before the features install.
FROM mcr.microsoft.com/devcontainers/java:1-21-bookworm
RUN rm -f /etc/apt/sources.list.d/yarn.list
