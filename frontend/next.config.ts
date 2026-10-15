import type { NextConfig } from "next";

/**
 * Cabeçalhos de segurança de todas as respostas. CSP sem nonce (a variante
 * "Without Nonces" da documentação do Next 16): mantém as páginas estáticas, ao
 * custo de `'unsafe-inline'` nos scripts, que o Next precisa para hidratar.
 * Mesmo assim bloqueia scripts e conexões de outras origens, plugins, `<base>`
 * trocado, formulários para fora e a página dentro de iframes.
 */
function cabecalhosDeSeguranca(): { key: string; value: string }[] {
  const producao = process.env.NODE_ENV === "production";
  const csp = [
    "default-src 'self'",
    // 'unsafe-eval' só em dev: o React o usa para reconstruir pilhas de erro do servidor.
    `script-src 'self' 'unsafe-inline'${producao ? "" : " 'unsafe-eval'"}`,
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' blob: data:",
    "font-src 'self'",
    "connect-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
    // Em dev (http://localhost) quebraria os recursos da própria página.
    ...(producao ? ["upgrade-insecure-requests"] : []),
  ].join("; ");

  return [
    { key: "Content-Security-Policy", value: csp },
    { key: "X-Content-Type-Options", value: "nosniff" },
    { key: "X-Frame-Options", value: "DENY" }, // navegadores antigos, sem frame-ancestors
    { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
    {
      key: "Permissions-Policy",
      value: "camera=(), microphone=(), geolocation=(), payment=(), usb=(), browsing-topics=()",
    },
    // Só em produção (HTTPS); o navegador ignora HSTS recebido por HTTP.
    ...(producao ? [{ key: "Strict-Transport-Security", value: "max-age=63072000; includeSubDomains" }] : []),
  ];
}

const nextConfig: NextConfig = {
  poweredByHeader: false, // sem "X-Powered-By: Next.js"
  async headers() {
    return [{ source: "/:path*", headers: cabecalhosDeSeguranca() }];
  },
};

export default nextConfig;
