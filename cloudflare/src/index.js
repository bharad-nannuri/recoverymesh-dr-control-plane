import { Container, getContainer } from "@cloudflare/containers";

const warmingPage = () => new Response(`<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="refresh" content="5"><title>RecoveryMesh is warming up</title>
<style>html,body{height:100%;margin:0;background:#070b10;color:#f4f7fb;font-family:Inter,system-ui,-apple-system,Segoe UI,sans-serif}body{display:grid;place-items:center}.box{width:min(560px,88vw);padding:34px;border:1px solid #223145;border-radius:20px;background:linear-gradient(180deg,#111925,#0c121a);box-shadow:0 20px 70px rgba(0,0,0,.35)}.ey{font-size:11px;letter-spacing:.16em;text-transform:uppercase;color:#5aa9ff;font-weight:800}h1{font-size:36px;line-height:1.05;margin:12px 0 10px}p{color:#9cabbf;line-height:1.6;margin:0 0 22px}.bar{height:8px;background:#171f2b;border-radius:99px;overflow:hidden}.bar:before{content:"";display:block;height:100%;width:35%;background:#5aa9ff;border-radius:99px;animation:move 1.2s ease-in-out infinite alternate}small{display:block;margin-top:14px;color:#66758a}@keyframes move{from{transform:translateX(-20%)}to{transform:translateX(210%)}}</style></head>
<body><div class="box"><div class="ey">RecoveryMesh DR Control Plane</div><h1>Warming up the recovery lab.</h1><p>PRIMARY PostgreSQL, SECONDARY PostgreSQL and the Spring Boot control plane are starting. This demo sleeps when idle to keep infrastructure cost low.</p><div class="bar"></div><small>This page refreshes automatically.</small></div></body></html>`, {status:503,headers:{"content-type":"text/html; charset=UTF-8","cache-control":"no-store","retry-after":"5"}});

export class RecoveryMeshContainer extends Container {
  defaultPort = 8080;
  requiredPorts = [8080];
  sleepAfter = "20m";
  enableInternet = false;

  async fetch(request) {
    try {
      await this.startAndWaitForPorts({
        ports:[8080],
        cancellationOptions:{instanceGetTimeoutMS:8000,portReadyTimeoutMS:5000,waitInterval:400}
      });
      return this.containerFetch(request);
    } catch (_) {
      return warmingPage();
    }
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/_edge/health") {
      return Response.json({edge:"healthy",runtime:"recoverymesh-cloudflare-container",stateModel:"ephemeral-demo"});
    }
    return getContainer(env.RECOVERYMESH, "portfolio-demo").fetch(request);
  }
};
