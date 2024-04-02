// Used example from https://github.com/lolgab/scalajs-vite-example
import { spawnSync } from "child_process";
import { defineConfig } from "vite";

function alias(mode) {
  if (mode === "development") return runMillTask("web.publicDev");
  if (mode === "production") return runMillTask("web.publicProd");
  const prefix = "test:";
  if (mode.startsWith(prefix))
    return {
      "@public": mode.substring(prefix.length),
    };
}

export default defineConfig(({ mode }) => {
  return {
    resolve: {
      alias: alias(mode),
    },
    server: {
      proxy: {
        '/api': {
          target: 'http://localhost:8080',
          changeOrigin: true,
          secure: false,
          ws: true,
          configure: (proxy, _options) => {
            proxy.on('error', (err, _req, _res) => {
              console.log('proxy error', err);
            });
            proxy.on('proxyReq', (proxyReq, req, _res) => {
              console.log('Sending Request to the Target:', req.method, req.url);
            });
            proxy.on('proxyRes', (proxyRes, req, _res) => {
              console.log('Received Response from the Target:', proxyRes.statusCode, req.url);
            });
          },
        },
      },
    },
  };
});

function runMillTask(task) {
  const result = spawnSync("mill", ["show", task], {
    stdio: [
      "pipe", // StdIn.
      "pipe", // StdOut.
      "inherit", // StdErr.
    ],
  });

  return JSON.parse(result.stdout);
}