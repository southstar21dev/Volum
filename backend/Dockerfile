FROM ghcr.io/muchobien/pocketbase:0.40.4

COPY pb_migrations /pb_migrations
COPY pb_hooks /pb_hooks
COPY start.sh /usr/local/bin/start.sh
RUN chmod +x /usr/local/bin/start.sh

EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8090/api/health || exit 1

ENTRYPOINT ["/usr/local/bin/start.sh"]
