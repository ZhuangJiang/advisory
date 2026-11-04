# syntax=docker/dockerfile:1
#
# Build context is the repository root so `deploy/nginx.conf` is available to the runtime stage.
# Vite output is static; nginx serves it and proxies `/api` to the backend, which keeps the browser
# on a single origin (no CORS) and is the only place SSE settings need to be right.

FROM node:22-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
# `npm run build` runs `vue-tsc --noEmit` first, so a type error fails the image, not production.
RUN npm run build

FROM nginx:alpine
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
