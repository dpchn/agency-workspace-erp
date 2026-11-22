/** @type {import('next').NextConfig} */
// The browser talks only to this server; /v1/* is proxied to the backend.
// So only one port must be exposed and CORS never applies.
// BACKEND_URL is read at build time (baked into the standalone routes manifest).
const backend = process.env.BACKEND_URL || "http://localhost:8080";

const nextConfig = {
  output: "standalone",
  reactStrictMode: true,
  async rewrites() {
    return [{ source: "/v1/:path*", destination: `${backend}/v1/:path*` }];
  },
};
export default nextConfig;
