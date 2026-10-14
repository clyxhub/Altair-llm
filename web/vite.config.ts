import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { viteSingleFile } from "vite-plugin-singlefile";

// The Android WebView loads dist/index.html from file:// — a single
// self-contained bundle avoids absolute-path and ES-module-CORS issues.
export default defineConfig({
  plugins: [react(), viteSingleFile()],
  base: "./",
  server: {
    proxy: {
      "/v1": "http://localhost:8787",
      "/health": "http://localhost:8787",
    },
  },
  build: {
    target: "es2020",
    outDir: "dist",
    sourcemap: false,
  },
});
