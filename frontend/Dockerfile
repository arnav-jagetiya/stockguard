# ==============================================================================
# STOCKGUARD — Frontend Multi-Stage Production Dockerfile
# React + TypeScript + Vite + Nginx Alpine
# ==============================================================================

# Stage 1: Build & Package
FROM node:22-alpine AS builder

WORKDIR /app

# Copy dependency manifests for layer caching
COPY package.json package-lock.json ./
RUN npm ci

# Copy frontend source files
COPY . .

# Compile TypeScript and bundle Vite production assets
RUN npm run build

# Stage 2: Nginx Reverse Proxy & Static Asset Server
FROM nginx:1.27-alpine AS runner

# Install openssl for fallback self-signed TLS certificate generation and wget for healthcheck
RUN apk add --no-cache openssl wget

WORKDIR /usr/share/nginx/html

# Remove default Nginx HTML files
RUN rm -rf ./*

# Copy compiled SPA static artifacts from builder stage
COPY --from=builder /app/dist ./

# Copy custom Nginx configuration and entrypoint
COPY nginx.conf /etc/nginx/nginx.conf
COPY entrypoint.sh /docker-entrypoint-custom.sh
RUN chmod +x /docker-entrypoint-custom.sh

# Expose HTTP (80) and HTTPS (443)
EXPOSE 80
EXPOSE 443

# Healthcheck probe querying internal health endpoint
HEALTHCHECK --interval=15s --timeout=5s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://127.0.0.1:80/healthz || exit 1

ENTRYPOINT ["/docker-entrypoint-custom.sh"]
CMD ["nginx", "-g", "daemon off;"]
