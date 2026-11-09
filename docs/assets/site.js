// Copy buttons on every code block and a light/dark switch (remembered in this browser only).
(() => {
  const root = document.documentElement;
  try { const saved = localStorage.getItem('apklab-docs-theme'); if (saved === 'light' || saved === 'dark') root.dataset.theme = saved; } catch { /* storage unavailable */ }
  const label = () => (root.dataset.theme ?? (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')) === 'dark' ? 'Tema claro' : 'Tema escuro';
  document.addEventListener('DOMContentLoaded', () => {
    const button = document.querySelector('button.theme');
    if (button) {
      button.textContent = label();
      button.addEventListener('click', () => {
        const current = root.dataset.theme ?? (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
        root.dataset.theme = current === 'dark' ? 'light' : 'dark';
        try { localStorage.setItem('apklab-docs-theme', root.dataset.theme); } catch { /* storage unavailable */ }
        button.textContent = label();
      });
    }
    for (const pre of document.querySelectorAll('pre')) {
      const copy = document.createElement('button');
      copy.type = 'button'; copy.className = 'copy'; copy.textContent = 'Copiar';
      copy.addEventListener('click', async () => {
        const text = pre.querySelector('code')?.innerText ?? pre.innerText;
        try { await navigator.clipboard.writeText(text.replace(/\n$/, '')); copy.textContent = 'Copiado'; }
        catch { copy.textContent = 'Selecione e copie'; }
        setTimeout(() => { copy.textContent = 'Copiar'; }, 1500);
      });
      pre.appendChild(copy);
    }
  });
})();
