const dgram = require("dgram");
// Local DNS forwarder for glibc processes (git): Android has no writable
// /etc/resolv.conf; glibc getaddrinfo defaults to 127.0.0.1:53 without it.
// Node's own resolution (c-ares, raw UDP) works on Android — forward there.
const upstream = ["1.1.1.1", "8.8.8.8"];
const sock = dgram.createSocket("udp4");
sock.on("error", (e) => { console.error("dns-forwarder:", e.message); process.exit(1); });
sock.on("message", (msg, rinfo) => {
  // log the queried name (bytes 12.. until zero)
  let name = ""; for (let i = 12; i < msg.length && msg[i]; i++) { name += String.fromCharCode(msg[i]).replace(/[^a-zA-Z0-9.-]/g, ""); if (msg[i] < 32) break; }
  console.log("query from", rinfo.address + ":" + rinfo.port, "name~", name);
  const q = dgram.createSocket("udp4");
  let done = false;
  const tryUp = (i) => {
    if (i >= upstream.length || done) { if (!done) q.close(); return; }
    const t = setTimeout(() => tryUp(i + 1), 2000);
    q.once("message", (ans) => { done = true; clearTimeout(t); q.close(); sock.send(ans, rinfo.port, rinfo.address); });
    q.send(msg, 53, upstream[i]);
  };
  tryUp(0);
});
sock.bind(15353, "127.0.0.1", () => console.log("dns-forwarder: listening 127.0.0.1:15353"));
