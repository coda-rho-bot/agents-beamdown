// dns-shim.js — Android has no /etc/resolv.conf, so glibc getaddrinfo fails (EAI_AGAIN).
// Replace dns.lookup (getaddrinfo) with c-ares resolve (raw UDP to public DNS).
const dns = require("dns");
const realLookup = dns.lookup.bind(dns);
dns.setServers(["8.8.8.8", "1.1.1.1"]);

function shimLookup(hostname, options, callback) {
  if (typeof options === "function") { callback = options; options = {}; }
  if (typeof options === "number") options = { family: options };
  options = options || {};
  const family = options.family || 0;
  const resolve = family === 6 ? "resolve6" : "resolve4";
  dns[resolve](hostname, (err, addresses) => {
    if (err) {
      // fall back to IPv6 if v4 fails and family unspecified
      if (family === 0) {
        return dns.resolve6(hostname, (e6, a6) => {
          if (e6) return callback(err);
          deliver(a6, 6);
        });
      }
      return callback(err);
    }
    deliver(addresses, 4);
  });
  function deliver(addresses, fam) {
    if (options.all) {
      return callback(null, addresses.map(address => ({ address, family: fam })));
    }
    callback(null, addresses[0], fam);
  }
}
dns.lookup = shimLookup;
try {
  const dp = require("dns/promises");
  const util = require("util");
  const p = util.promisify(shimLookup);
  dp.lookup = (hostname, options) => p(hostname, options || {});
} catch {}
