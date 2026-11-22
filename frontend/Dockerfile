# ---- deps
FROM node:20.18.1-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

# ---- build (NEXT_PUBLIC_* are baked in at build time)
FROM node:20.18.1-alpine AS build
WORKDIR /app
ARG NEXT_PUBLIC_API_URL=http://localhost:8080
ARG NEXT_PUBLIC_BRAND_NAME=DutyGrid
ARG NEXT_PUBLIC_BRAND_TAGLINE="Workforce ERP for security & facility agencies"
ARG NEXT_PUBLIC_BRAND_PRIMARY=#1d4ed8
ARG NEXT_PUBLIC_BRAND_LOGO=/brand/logo.svg
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL NEXT_PUBLIC_BRAND_NAME=$NEXT_PUBLIC_BRAND_NAME \
    NEXT_PUBLIC_BRAND_TAGLINE=$NEXT_PUBLIC_BRAND_TAGLINE NEXT_PUBLIC_BRAND_PRIMARY=$NEXT_PUBLIC_BRAND_PRIMARY \
    NEXT_PUBLIC_BRAND_LOGO=$NEXT_PUBLIC_BRAND_LOGO NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

# ---- runtime (standalone server)
FROM node:20.18.1-alpine AS run
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
COPY --from=build /app/public ./public
USER app
EXPOSE 3000
CMD ["node", "server.js"]
