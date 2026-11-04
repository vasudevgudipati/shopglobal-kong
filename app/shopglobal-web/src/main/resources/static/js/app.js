/* ShopGlobal storefront – plain JS single-page app (hash routing). English only; Hindi lives in the chat window. */
(function () {
  'use strict';

  const CATEGORIES = ["Men's Fashion", "Women's Fashion", 'Footwear', 'Electronics', 'Home & Kitchen', 'Beauty', 'Sports & Fitness'];
  const CAT_ART = {
    "Men's Fashion": ['👔', '#dbe7ff'], "Women's Fashion": ['👗', '#ffe1ee'], 'Footwear': ['👟', '#ffeed2'],
    'Electronics': ['🎧', '#e0e8ff'], 'Home & Kitchen': ['🍳', '#e3f5e7'], 'Beauty': ['💄', '#fde1f5'], 'Sports & Fitness': ['🏋️', '#e1f3f7']
  };
  const GLYPH = {
    Trousers: '👖', Jeans: '👖', Shirts: '👔', 'T-Shirts': '👕', Jackets: '🧥', Dresses: '👗', Kurtas: '👘', Tops: '👚',
    Sneakers: '👟', 'Running Shoes': '👟', 'Formal Shoes': '👞', Sandals: '🩴', Headphones: '🎧', Earbuds: '🎧',
    Smartwatches: '⌚', Speakers: '🔊', Cookware: '🍳', Bedding: '🛏️', Appliances: '🫖', Skincare: '🧴', Makeup: '💄',
    Yoga: '🧘', Weights: '🏋️'
  };
  const TINTS = [['black', '#3b4252'], ['white', '#f4f6fa'], ['navy', '#2d4a8a'], ['olive', '#8a9a5b'], ['khaki', '#cbb68a'], ['beige', '#e3d4b8'],
    ['sand', '#e3d4b8'], ['sky', '#9ccbf0'], ['blue', '#6aa0e8'], ['indigo', '#4a63b5'], ['grey', '#a3acba'], ['charcoal', '#59606e'], ['pink', '#f2a9c4'],
    ['maroon', '#a8324f'], ['rust', '#c4673a'], ['teal', '#3fa6a0'], ['mustard', '#e0b030'], ['lilac', '#c7b2ee'], ['purple', '#9b6fd6'], ['red', '#e0525a'],
    ['green', '#58b57a'], ['cream', '#f3ead3'], ['steel', '#b9c2cf'], ['tan', '#c9a27a'], ['brown', '#8a5a3c'], ['silver', '#cfd5de'], ['rose', '#efb1a0'], ['light', '#bcd6f2'], ['dark', '#35466b']];

  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
  const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const inr = n => '₹' + Number(n).toLocaleString('en-IN');
  const view = $('#view');

  // ---------------------------------------------------------------- storage (never required)
  const store = {
    get(k, d) { try { const v = localStorage.getItem(k); return v ? JSON.parse(v) : d; } catch (e) { return d; } },
    set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* ignore */ } }
  };
  let cart = store.get('sg.cart', []);
  let wish = store.get('sg.wish', []);
  const saveCart = () => { store.set('sg.cart', cart); updateCartCount(); };
  function updateCartCount() { $('#cartCount').textContent = cart.reduce((a, i) => a + i.qty, 0); }

  function toast(msg) {
    const t = $('#toast'); t.textContent = msg; t.classList.add('show');
    clearTimeout(toast.t); toast.t = setTimeout(() => t.classList.remove('show'), 2400);
  }

  // ---------------------------------------------------------------- api
  async function api(path, opts) {
    const r = await fetch('/api' + path, opts);
    let body = null; try { body = await r.json(); } catch (e) { /* no body */ }
    if (r.status === 401) { setUser(null); toast('Please sign in to continue'); location.hash = '#/login?next=' + encodeURIComponent(location.hash.replace(/^#/, '') || '/'); }
    if (!r.ok) { const err = new Error((body && body.message) || 'Something went wrong'); err.status = r.status; throw err; }
    return body;
  }

  // ---------------------------------------------------------------- sign-in (demo users; the server keeps the session)
  let user = null;   // { username, name } or null
  window.sgUser = () => user;
  function setUser(u) {
    const changed = (user && user.username) !== (u && u.username);
    user = u;
    const a = $('#acct');
    a.href = u ? '#/' : '#/login';
    a.innerHTML = u ? `<span class="ha-ico">👤</span><span class="ha-txt"><small>Hello, ${esc(u.name.split(' ')[0])}</small>Sign out</span>`
                    : '<span class="ha-ico">👤</span><span class="ha-txt"><small>Hello, sign in</small>Account</span>';
    if (changed && window.chatReset) window.chatReset();   // a conversation belongs to one customer
  }
  async function loadUser() {
    try { const m = await fetch('/auth/me').then(r => r.json()); setUser(m.signedIn ? m : null); } catch (e) { setUser(null); }
  }
  async function signOut() {
    try { await fetch('/auth/logout', { method: 'POST' }); } catch (e) { /* ignore */ }
    setUser(null); cart = []; saveCart(); updateCartCount(); toast('You have been signed out'); location.hash = '#/';
  }
  const DEMO_USERS = [['vasudev', 'Vasudev Gudipati'], ['tataji', 'Tataji'], ['shubham', 'Shubham'], ['kvamshi', 'Kvamshi']];
  function login(params) {
    const next = params.get('next') || '/';
    view.innerHTML = `<div class="login"><div class="panel"><h1>Sign in</h1><p class="muted">Sign in to place orders, see your orders and chat with the assistant.</p>
      <form id="loginForm"><label>User name<input name="username" autocomplete="username" required autofocus></label>
      <label>Password<input name="password" type="password" autocomplete="current-password" required></label>
      <p class="login-err" id="loginErr" role="alert"></p><button class="btn btn-primary btn-block" id="loginBtn">Sign in</button></form>
      ${document.body.dataset.env === 'UAT' ? `<div class="demo-users"><b>Demo customers</b><small>Click one to fill the user name. Password: <code>welcome@123</code></small>
        <div>${DEMO_USERS.map(([u, n]) => `<button type="button" class="chip" data-u="${u}">${esc(n)}</button>`).join('')}</div></div>` : ''}</div></div>`;
    $$('[data-u]').forEach(b => b.addEventListener('click', () => { const f = $('#loginForm'); f.username.value = b.dataset.u; f.password.focus(); }));
    $('#loginForm').addEventListener('submit', async e => {
      e.preventDefault(); const f = e.target, btn = $('#loginBtn'); btn.disabled = true; btn.textContent = 'Signing in…'; $('#loginErr').textContent = '';
      try {
        const r = await fetch('/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: f.username.value, password: f.password.value }) });
        const m = await r.json().catch(() => ({}));
        if (!r.ok) throw new Error(m.message || 'Could not sign in');
        setUser(m); toast('Welcome, ' + m.name.split(' ')[0]); location.hash = '#' + (next.startsWith('/') ? next : '/');
      } catch (err) { $('#loginErr').textContent = err.message; btn.disabled = false; btn.textContent = 'Sign in'; }
    });
  }
  const PCACHE = {};
  const remember = list => list.forEach(p => { PCACHE[p.id] = p; });

  // ---------------------------------------------------------------- product art (generated SVG, no image files needed)
  function tint(name) {
    const n = (name || '').toLowerCase();
    for (const [k, v] of TINTS) if (n.includes(k)) return v;
    return '#9fb4d8';
  }
  function shade(hex, amt) {
    const n = parseInt(hex.slice(1), 16); let r = n >> 16, g = (n >> 8) & 255, b = n & 255;
    const f = c => Math.max(0, Math.min(255, Math.round(c + (amt > 0 ? (255 - c) : c) * amt)));
    return '#' + [f(r), f(g), f(b)].map(x => x.toString(16).padStart(2, '0')).join('');
  }
  function glyphFor(p) {
    if (p.category === 'Sports & Fitness' && p.subCategory === 'Accessories') return '🍶';
    if (p.category === 'Electronics' && p.subCategory === 'Accessories') return '🔋';
    return GLYPH[p.subCategory] || '🛍️';
  }
  function art(p, color, view) {
    const base = tint(color || (p.colors && p.colors[0]));
    const bg1 = shade(base, 0.72), bg2 = shade(base, 0.45);
    const v = view || 0;
    const rot = [0, -8, 6, 0][v], sc = [1, 1.05, 0.9, 1.45][v];
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 420"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${bg1}"/><stop offset="1" stop-color="${bg2}"/></linearGradient></defs>`
      + `<rect width="400" height="420" fill="url(#g)"/><circle cx="${v % 2 ? 90 : 320}" cy="${v % 2 ? 340 : 80}" r="120" fill="#fff" opacity=".18"/><circle cx="${v % 2 ? 340 : 70}" cy="${v % 2 ? 60 : 350}" r="70" fill="#fff" opacity=".14"/>`
      + `<ellipse cx="200" cy="350" rx="${110 * sc}" ry="14" fill="#000" opacity=".12"/>`
      + `<g transform="translate(200 215) rotate(${rot}) scale(${sc})"><text text-anchor="middle" dominant-baseline="central" font-size="190" font-family="Apple Color Emoji,Segoe UI Emoji,Noto Color Emoji,sans-serif">${glyphFor(p)}</text></g>`
      + `<text x="200" y="396" text-anchor="middle" font-size="19" font-weight="700" letter-spacing="3" fill="#1c2230" opacity=".55" font-family="Arial,sans-serif">${esc((p.brand || '').toUpperCase())}</text></svg>`;
    return 'data:image/svg+xml;utf8,' + encodeURIComponent(svg);
  }

  // ---------------------------------------------------------------- shared fragments
  const off = p => Math.round((1 - p.price / p.mrp) * 100);
  const stars = r => `<span class="rating ${r < 4 ? 'mid' : ''}">${Number(r).toFixed(1)} ★</span>`;

  function card(p) {
    const w = wish.includes(p.id);
    const tag = p.tag ? `<span class="tag">${esc(p.tag)}</span>` : (off(p) >= 45 ? `<span class="tag deal">${off(p)}% OFF</span>` : '');
    return `<article class="card"><a href="#/product/${p.id}" class="img"><img loading="lazy" alt="${esc(p.name)}" src="${art(p)}"></a>${tag}
      <button class="heart ${w ? 'on' : ''}" data-wish="${p.id}" aria-label="Add to wishlist">${w ? '♥' : '♡'}</button>
      <div class="body"><span class="brand">${esc(p.brand)}</span><a class="name" href="#/product/${p.id}">${esc(p.name)}</a>
      <div>${stars(p.rating)}<span class="count">(${p.ratingCount.toLocaleString('en-IN')})</span></div>
      <div class="price"><b>${inr(p.price)}</b><s>${inr(p.mrp)}</s><span class="off">${off(p)}% off</span></div></div></article>`;
  }

  function bindWish(root) {
    $$('[data-wish]', root).forEach(b => b.addEventListener('click', e => {
      e.preventDefault(); const id = b.dataset.wish;
      wish = wish.includes(id) ? wish.filter(x => x !== id) : wish.concat(id);
      store.set('sg.wish', wish); b.classList.toggle('on'); b.textContent = b.classList.contains('on') ? '♥' : '♡';
    }));
  }

  const skeleton = n => `<div class="grid">${'<div class="skeleton"></div>'.repeat(n)}</div>`;
  const fail = e => `<div class="empty"><i>⚠️</i><h2>We couldn't load this</h2><p>${esc(e.message)}</p><button class="btn btn-primary" onclick="location.reload()">Try again</button></div>`;

  // ---------------------------------------------------------------- views
  async function home() {
    view.innerHTML = `
      <section class="hero" id="hero">
        <div class="slide on s1"><div><h2>Big Style Sale</h2><p>Up to 60% off on fashion, footwear and more. Fresh deals every day.</p><a class="btn btn-primary" href="#/search?category=Men%27s%20Fashion">Shop men's fashion</a></div><div class="art">🧥<small>👖</small></div></div>
        <div class="slide s2"><div><h2>New season, new you</h2><p>Dresses, kurtas and tops – refreshed for the festive season.</p><a class="btn btn-yellow" href="#/search?category=Women%27s%20Fashion">Explore women's</a></div><div class="art">👗</div></div>
        <div class="slide s3"><div><h2>Tech you'll love</h2><p>Headphones, smartwatches and accessories at their lowest prices.</p><a class="btn btn-primary" href="#/search?category=Electronics">Shop electronics</a></div><div class="art">🎧</div></div>
        <div class="dots"><i class="on"></i><i></i><i></i></div>
      </section>
      <section class="tiles">${CATEGORIES.map(c => `<a class="tile" href="#/search?category=${encodeURIComponent(c)}"><div class="bubble" style="background:${CAT_ART[c][1]}">${CAT_ART[c][0]}</div><span>${esc(c)}</span></a>`).join('')}</section>
      <div id="homeRows">${skeleton(6)}</div>
      <section class="promo">
        <div style="background:linear-gradient(120deg,#e8590c,#ff922b)"><b>Extra 10% off</b><span>On your first order with UPI</span><em>🎁</em></div>
        <div style="background:linear-gradient(120deg,#2b8a3e,#51cf66)"><b>Easy returns</b><span>Hassle-free returns on most items</span><em>↩️</em></div>
        <div style="background:linear-gradient(120deg,#5f3dc4,#9775fa)"><b>Ask our assistant</b><span>Find products and track orders by chat</span><em>💬</em></div>
      </section>
      <section class="trust"><div><i>🚚</i><span><b>Fast delivery</b><small>Across India</small></span></div><div><i>🔒</i><span><b>Secure payments</b><small>UPI, cards, COD</small></span></div><div><i>✅</i><span><b>100% genuine</b><small>Verified sellers</small></span></div><div><i>🎧</i><span><b>24×7 support</b><small>Chat with us anytime</small></span></div></section>`;
    startHero();
    try {
      const all = await api('/products?sort=rating&limit=200'); remember(all.products);
      const deals = [...all.products].sort((a, b) => off(b) - off(a)).slice(0, 6);
      const rows = [`<section class="section"><div class="section-head"><h2>🔥 Deals of the day</h2><a href="#/search?sort=discount">See all</a></div><div class="strip">${deals.map(card).join('')}</div></section>`];
      ["Men's Fashion", 'Electronics', 'Footwear'].forEach(c => {
        const l = all.products.filter(p => p.category === c).slice(0, 6);
        if (l.length) rows.push(`<section class="section"><div class="section-head"><h2>Trending in ${esc(c)}</h2><a href="#/search?category=${encodeURIComponent(c)}">View more</a></div><div class="strip">${l.map(card).join('')}</div></section>`);
      });
      $('#homeRows').innerHTML = rows.join(''); bindWish(view);
    } catch (e) { $('#homeRows').innerHTML = fail(e); }
  }

  let heroTimer;
  function startHero() {
    clearInterval(heroTimer);
    const slides = $$('#hero .slide'), dots = $$('#hero .dots i'); let i = 0;
    const show = n => { i = n; slides.forEach((s, k) => s.classList.toggle('on', k === n)); dots.forEach((d, k) => d.classList.toggle('on', k === n)); };
    dots.forEach((d, k) => d.addEventListener('click', () => show(k)));
    heroTimer = setInterval(() => { if (!$('#hero')) return clearInterval(heroTimer); show((i + 1) % slides.length); }, 5000);
  }

  async function search(params) {
    const q = params.get('q') || '', category = params.get('category') || '', sort = params.get('sort') || 'relevance';
    view.innerHTML = `<div class="crumbs"><a href="#/">Home</a> › ${esc(category || (q ? 'Search' : 'All products'))}</div>${skeleton(8)}`;
    let data;
    try { data = await api('/products?' + new URLSearchParams({ ...(q && { q }), ...(category && { category }), sort, limit: 200 })); } catch (e) { view.innerHTML = fail(e); return; }
    remember(data.products);
    const all = data.products;
    const f = { brands: new Set(), size: '', price: '', rating: false };
    const brands = [...new Set(all.map(p => p.brand))].sort();
    const sizes = [...new Set(all.flatMap(p => p.sizes))];
    const PRICE = [['', 'Any price'], ['0-999', 'Under ₹1,000'], ['1000-2499', '₹1,000 – ₹2,499'], ['2500-4999', '₹2,500 – ₹4,999'], ['5000-999999', '₹5,000 & above']];
    const title = q ? `Results for “${esc(q)}”` : (category ? esc(category) : 'All products');

    function paint() {
      const list = all.filter(p => (!f.brands.size || f.brands.has(p.brand)) && (!f.size || p.sizes.includes(f.size)) && (!f.rating || p.rating >= 4)
        && (!f.price || (p.price >= +f.price.split('-')[0] && p.price <= +f.price.split('-')[1])));
      $('#grid').innerHTML = list.length ? `<div class="grid">${list.map(card).join('')}</div>`
        : `<div class="empty"><i>🔎</i><h2>No matching products</h2><p>Try removing a filter or searching with different words.</p></div>`;
      $('#cnt').textContent = `${list.length} item${list.length === 1 ? '' : 's'}`; bindWish($('#grid'));
    }
    view.innerHTML = `<div class="crumbs"><a href="#/">Home</a> › ${esc(category || 'Search')}</div>
      <div class="listing"><aside class="filters"><h3>Filters</h3>
        <div class="fgroup"><h4>Brand</h4>${brands.map(b => `<label><input type="checkbox" data-brand="${esc(b)}"> ${esc(b)}</label>`).join('') || '<small>–</small>'}</div>
        <div class="fgroup"><h4>Price</h4>${PRICE.map(([v, l]) => `<label><input type="radio" name="price" value="${v}" ${v === '' ? 'checked' : ''}> ${l}</label>`).join('')}</div>
        ${sizes.length ? `<div class="fgroup"><h4>Size</h4><div class="chips">${sizes.map(s => `<button data-size="${esc(s)}">${esc(s)}</button>`).join('')}</div></div>` : ''}
        <div class="fgroup"><h4>Customer rating</h4><label><input type="checkbox" id="r4"> 4★ &amp; above</label></div></aside>
      <section><div class="listing-head"><h1>${title} <small id="cnt"></small></h1>
        <select class="sort" id="sort" aria-label="Sort by">${[['relevance', 'Sort: Relevance'], ['price_asc', 'Price: Low to High'], ['price_desc', 'Price: High to Low'], ['rating', 'Customer rating'], ['discount', 'Biggest discount']].map(([v, l]) => `<option value="${v}" ${v === sort ? 'selected' : ''}>${l}</option>`).join('')}</select></div>
        <div id="grid"></div></section></div>`;
    $('#sort').addEventListener('change', e => { params.set('sort', e.target.value); location.hash = '#/search?' + params.toString(); });
    $$('[data-brand]').forEach(c => c.addEventListener('change', () => { c.checked ? f.brands.add(c.dataset.brand) : f.brands.delete(c.dataset.brand); paint(); }));
    $$('[name=price]').forEach(r => r.addEventListener('change', () => { f.price = r.value; paint(); }));
    $$('[data-size]').forEach(b => b.addEventListener('click', () => { const on = f.size === b.dataset.size; f.size = on ? '' : b.dataset.size; $$('[data-size]').forEach(x => x.classList.toggle('on', x.dataset.size === f.size)); paint(); }));
    $('#r4').addEventListener('change', e => { f.rating = e.target.checked; paint(); });
    paint();
  }

  async function product(id) {
    view.innerHTML = skeleton(1);
    let p, pol = null, sim = [];
    try { p = await api('/products/' + encodeURIComponent(id)); } catch (e) { view.innerHTML = fail(e); return; }
    remember([p]);
    try { pol = await api('/return-policy?productId=' + p.id); } catch (e) { /* optional */ }
    try { sim = (await api('/products?limit=12&category=' + encodeURIComponent(p.subCategory))).products.filter(x => x.id !== p.id).slice(0, 6); } catch (e) { /* optional */ }
    const sel = { color: p.colors[0], size: '', qty: 1, view: 0 };
    const hasSize = p.sizes.length > 0;
    view.innerHTML = `<div class="crumbs"><a href="#/">Home</a> › <a href="#/search?category=${encodeURIComponent(p.category)}">${esc(p.category)}</a> › <a href="#/search?category=${encodeURIComponent(p.subCategory)}">${esc(p.subCategory)}</a></div>
      <div class="pdp"><div class="gallery"><div class="thumbs" id="thumbs"></div><div class="mainimg"><img id="mainimg" alt="${esc(p.name)}"></div></div>
      <div><a class="brandlink" href="#/search?q=${encodeURIComponent(p.brand)}">Visit the ${esc(p.brand)} store</a><h1>${esc(p.name)}</h1>
        <div class="rrow">${stars(p.rating)}<span class="count">${p.ratingCount.toLocaleString('en-IN')} ratings</span>${p.tag ? `<span class="badge">${esc(p.tag)}</span>` : ''}</div>
        <div class="bigprice"><b>${inr(p.price)}</b><s>${inr(p.mrp)}</s><span>${off(p)}% off</span></div><div class="tax">Inclusive of all taxes · You save ${inr(p.mrp - p.price)}</div>
        ${p.colors.length > 1 ? `<div class="opt"><h4>Colour: <span id="colorLbl">${esc(sel.color)}</span></h4><div class="swatches" id="colors">${p.colors.map((c, i) => `<button class="${i ? '' : 'on'}" data-c="${esc(c)}">${esc(c)}</button>`).join('')}</div></div>` : ''}
        ${hasSize ? `<div class="opt"><h4>${esc(p.sizeType || 'Size')} <span id="sizeMsg"></span></h4><div class="sizes" id="sizes">${p.sizes.map(s => `<button data-s="${esc(s)}">${esc(s)}</button>`).join('')}</div></div>` : ''}
        <div class="opt"><h4>Quantity</h4><div class="qty"><button id="qm">−</button><span id="qv">1</span><button id="qp">+</button></div> ${p.stock < 20 ? `<span class="stock-low"> Only ${p.stock} left in stock</span>` : ''}</div>
        <div class="cta"><button class="btn btn-yellow" id="add">Add to cart</button><button class="btn btn-primary" id="buy">Buy now</button></div>
        <div class="box"><h4>Check delivery</h4><div class="deliv"><input id="pin" inputmode="numeric" maxlength="6" placeholder="Enter 6-digit pincode"><button class="btn btn-ghost" id="pinBtn">Check</button></div><div id="pinMsg" style="margin-top:8px;font-size:14px"></div></div>
        <div class="box"><h4>Offers</h4><ul><li>10% instant discount on first UPI order</li><li>No-cost EMI on orders above ₹3,000</li></ul></div>
        <div class="box"><h4>Product highlights</h4><ul>${p.highlights.map(h => `<li>${esc(h)}</li>`).join('')}${p.fit ? `<li>Fit: ${esc(p.fit)}</li>` : ''}</ul><p style="margin:10px 0 0;color:var(--muted)">${esc(p.description)}</p></div>
        <div class="box"><h4>Returns</h4>${pol ? `<span class="${pol.returnable ? 'good' : 'bad'}">${pol.returnable ? pol.returnWindowDays + '-day returns' : 'Limited returns'}</span> – ${esc(pol.summary)}` : 'Return details are shown at checkout.'}</div>
        <div class="perks"><div><i>↩️</i>Easy returns</div><div><i>🚚</i>Fast delivery</div><div><i>✅</i>Genuine</div><div><i>🔒</i>Secure pay</div></div></div></div>
      ${sim.length ? `<section class="section"><div class="section-head"><h2>You may also like</h2></div><div class="strip">${sim.map(card).join('')}</div></section>` : ''}`;
    const paintImg = () => {
      $('#mainimg').src = art(p, sel.color, sel.view);
      $('#thumbs').innerHTML = [0, 1, 2, 3].map(v => `<button class="${v === sel.view ? 'on' : ''}" data-v="${v}"><img alt="view ${v + 1}" src="${art(p, sel.color, v)}"></button>`).join('');
      $$('#thumbs button').forEach(b => b.addEventListener('click', () => { sel.view = +b.dataset.v; paintImg(); }));
    };
    paintImg();
    $$('#colors button').forEach(b => b.addEventListener('click', () => { sel.color = b.dataset.c; $('#colorLbl').textContent = sel.color; $$('#colors button').forEach(x => x.classList.toggle('on', x === b)); paintImg(); }));
    $$('#sizes button').forEach(b => b.addEventListener('click', () => { sel.size = b.dataset.s; $('#sizeMsg').textContent = ''; $$('#sizes button').forEach(x => x.classList.toggle('on', x === b)); }));
    $('#qm').addEventListener('click', () => { sel.qty = Math.max(1, sel.qty - 1); $('#qv').textContent = sel.qty; });
    $('#qp').addEventListener('click', () => { sel.qty = Math.min(10, sel.qty + 1); $('#qv').textContent = sel.qty; });
    $('#pinBtn').addEventListener('click', () => {
      const v = $('#pin').value.trim(), m = $('#pinMsg');
      if (!/^\d{6}$/.test(v)) { m.innerHTML = '<span class="bad">Enter a valid 6-digit pincode</span>'; return; }
      const d = new Date(Date.now() + 4 * 864e5).toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short' });
      m.innerHTML = `<span class="good">Delivery by ${d}</span> · Free delivery above ₹999`;
    });
    const addToCart = () => {
      if (hasSize && !sel.size) { $('#sizeMsg').innerHTML = '<span class="bad">– please select a size</span>'; $('#sizes').scrollIntoView({ block: 'center', behavior: 'smooth' }); return false; }
      const ex = cart.find(i => i.id === p.id && i.size === sel.size && i.color === sel.color);
      if (ex) ex.qty = Math.min(10, ex.qty + sel.qty);
      else cart.push({ id: p.id, name: p.name, brand: p.brand, price: p.price, mrp: p.mrp, size: sel.size, color: sel.color, qty: sel.qty, category: p.category, subCategory: p.subCategory, colors: p.colors });
      saveCart(); return true;
    };
    $('#add').addEventListener('click', () => { if (addToCart()) toast('Added to cart'); });
    $('#buy').addEventListener('click', () => { if (addToCart()) location.hash = '#/checkout'; });
    bindWish(view);
  }

  function cartView() {
    if (!cart.length) { view.innerHTML = `<div class="empty"><i>🛒</i><h2>Your cart is empty</h2><p>Add something you like and it will show up here.</p><a class="btn btn-primary" href="#/">Continue shopping</a></div>`; return; }
    const sub = cart.reduce((a, i) => a + i.price * i.qty, 0), mrp = cart.reduce((a, i) => a + i.mrp * i.qty, 0), ship = sub >= 999 ? 0 : 79;
    view.innerHTML = `<h1 class="page-title">Shopping cart <small style="font-size:15px;color:var(--muted)">(${cart.reduce((a, i) => a + i.qty, 0)} items)</small></h1><div class="two">
      <div class="panel">${cart.map((i, k) => `<div class="crow"><img alt="" src="${art(i, i.color)}"><div><h3><a href="#/product/${i.id}">${esc(i.name)}</a></h3><small>${esc(i.brand)}${i.size ? ' · Size ' + esc(i.size) : ''} · ${esc(i.color)}</small>
        <div class="price"><b>${inr(i.price)}</b><s>${inr(i.mrp)}</s><span class="off">${off(i)}% off</span></div>
        <div class="links"><div class="qty"><button data-q="${k}" data-d="-1">−</button><span>${i.qty}</span><button data-q="${k}" data-d="1">+</button></div><button class="linkbtn" data-rm="${k}">Remove</button></div></div><b>${inr(i.price * i.qty)}</b></div>`).join('')}</div>
      <aside class="panel sum"><h3 style="margin-bottom:8px">Price details</h3><div><span>Price (${cart.length} products)</span><span>${inr(mrp)}</span></div><div class="save"><span>Discount</span><span>− ${inr(mrp - sub)}</span></div>
        <div><span>Delivery</span><span>${ship ? inr(ship) : '<span class="good">FREE</span>'}</span></div><div class="total"><span>Total</span><span>${inr(sub + ship)}</span></div>
        <p class="good" style="margin:6px 0 14px">You will save ${inr(mrp - sub)} on this order</p><a class="btn btn-primary btn-block" href="#/checkout" style="display:block;text-align:center">Proceed to checkout</a></aside></div>`;
    $$('[data-q]').forEach(b => b.addEventListener('click', () => { const i = cart[+b.dataset.q]; i.qty = Math.max(1, Math.min(10, i.qty + +b.dataset.d)); saveCart(); cartView(); }));
    $$('[data-rm]').forEach(b => b.addEventListener('click', () => { cart.splice(+b.dataset.rm, 1); saveCart(); cartView(); }));
  }

  async function checkout() {
    if (!cart.length) { location.hash = '#/cart'; return; }
    let saved = []; try { saved = await api('/addresses'); } catch (e) { saved = []; }   // the signed-in customer's own saved addresses
    const a0 = (saved[0] && saved[0].address) || {}, v = k => esc(a0[k] || (k === 'name' && user ? user.name : ''));
    const sub = cart.reduce((a, i) => a + i.price * i.qty, 0), ship = sub >= 999 ? 0 : 79;
    view.innerHTML = `<h1 class="page-title">Checkout</h1><form class="two" id="coForm"><div>
      <div class="panel" style="margin-bottom:18px"><h3 style="margin-bottom:14px">1. Delivery address</h3><div class="form">
        ${saved.length ? `<label class="full">Saved address<select id="savedAddr">${saved.map((x, i) => `<option value="${i}">${esc(x.label)} – ${esc(x.address.city)}</option>`).join('')}</select></label>` : ''}
        <label>Full name<input name="name" required value="${v('name')}"></label><label>Mobile number<input name="phone" required inputmode="numeric" maxlength="10" pattern="\\d{10}" value="${v('phone')}"></label>
        <label class="full">Address line 1<input name="line1" required value="${v('line1')}"></label><label class="full">Address line 2 (optional)<input name="line2" value="${v('line2')}"></label>
        <label>City<input name="city" required value="${v('city')}"></label><label>State<input name="state" required value="${v('state')}"></label><label>Pincode<input name="pincode" required inputmode="numeric" maxlength="6" pattern="\\d{6}" value="${v('pincode')}"></label></div></div>
      <div class="panel pay"><h3 style="margin-bottom:8px">2. Payment method</h3>
        <label><input type="radio" name="pay" value="UPI" checked> 📱 UPI (Google Pay, PhonePe, Paytm)</label><label><input type="radio" name="pay" value="Card"> 💳 Credit / Debit card</label><label><input type="radio" name="pay" value="COD"> 💵 Cash on delivery</label></div></div>
      <aside class="panel sum"><h3 style="margin-bottom:8px">Order summary</h3>${cart.map(i => `<div><span>${esc(i.name)} × ${i.qty}</span><span>${inr(i.price * i.qty)}</span></div>`).join('')}
        <div><span>Delivery</span><span>${ship ? inr(ship) : 'FREE'}</span></div><div class="total"><span>Order total</span><span>${inr(sub + ship)}</span></div>
        <button class="btn btn-primary btn-block" id="place" style="margin-top:14px">Place order</button><p class="tax" style="text-align:center">This is a demo store – no real payment is taken.</p></aside></form>`;
    const sa = $('#savedAddr');
    if (sa) sa.addEventListener('change', () => { const a = saved[+sa.value].address, f = $('#coForm'); ['name', 'phone', 'line1', 'line2', 'city', 'state', 'pincode'].forEach(k => { f.elements[k].value = a[k] || ''; }); });
    $('#coForm').addEventListener('submit', async e => {
      e.preventDefault(); const fd = new FormData(e.target), btn = $('#place'); btn.disabled = true; btn.textContent = 'Placing order…';
      const body = { payment: fd.get('pay'), address: ['name', 'line1', 'line2', 'city', 'state', 'pincode', 'phone'].reduce((o, k) => (o[k] = (fd.get(k) || '').trim(), o), {}),
        items: cart.map(i => ({ productId: i.id, qty: i.qty, size: i.size || null, color: i.color })) };
      try {
        const o = await api('/orders', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
        cart = []; saveCart(); location.hash = '#/order-success/' + o.orderId;
      } catch (err) { btn.disabled = false; btn.textContent = 'Place order'; toast(err.message); }
    });
  }

  async function orders() {
    view.innerHTML = `<h1 class="page-title">Your orders</h1>${skeleton(2)}`;
    let list; try { list = await api('/orders'); } catch (e) { view.innerHTML = fail(e); return; }
    try { remember((await api('/products?limit=200')).products); } catch (e) { /* images fall back to a generic art */ }
    const help = `<div class="help"><span style="font-size:28px">💬</span><div><b>Need help with an order?</b><small>Ask our assistant to track, cancel, change the address or explain returns.</small></div><button class="btn btn-primary" data-chat="I need help with my orders">Chat with assistant</button></div>`;
    view.innerHTML = `<h1 class="page-title">Your orders</h1>${help}${list.map(o => `<div class="order"><div class="order-h"><div>ORDER PLACED<b>${esc(o.placedOn)}</b></div><div>TOTAL<b>${inr(o.orderTotal)}</b></div><div>SHIP TO<b>${esc(o.address.name)}</b></div>
      <div class="right">ORDER # <a href="#/orders/${o.orderId}"><b style="color:var(--blue)">${o.orderId}</b></a></div></div>
      <div class="order-b">${o.items.map(i => `<div class="oi"><img alt="" src="${art(PCACHE[i.productId] || { subCategory: '', category: '', brand: i.brand, colors: [i.color] }, i.color)}"><div><h4>${esc(i.name)}</h4><small>${esc(i.brand)}${i.size ? ' · Size ' + esc(i.size) : ''} · Qty ${i.qty} · ${inr(i.unitPrice)}</small></div><span class="badge ${i.status}">${i.status.replace(/_/g, ' ')}</span></div>`).join('')}</div></div>`).join('') || '<div class="empty"><i>📦</i><h2>No orders yet</h2></div>'}`;
    bindChat();
  }

  async function orderDetail(id) {
    view.innerHTML = skeleton(1); let o;
    try { o = await api('/orders/' + encodeURIComponent(id)); } catch (e) { view.innerHTML = fail(e); return; }
    try { remember((await api('/products?limit=200')).products); } catch (e) { /* optional */ }
    const steps = ['PLACED', 'PACKED', 'SHIPPED', 'OUT_FOR_DELIVERY', 'DELIVERED'], at = steps.indexOf(o.status);
    view.innerHTML = `<div class="crumbs"><a href="#/orders">Your orders</a> › ${o.orderId}</div><div class="panel"><div style="display:flex;justify-content:space-between;flex-wrap:wrap;gap:8px"><h1 class="page-title" style="margin:0">Order ${o.orderId}</h1><span class="badge ${o.status}">${o.status.replace(/_/g, ' ')}</span></div>
      <p class="tax">Placed on ${esc(o.placedOn)} · Payment: ${esc(o.payment)}${o.expectedDelivery && o.status !== 'DELIVERED' && o.status !== 'CANCELLED' ? ' · Expected delivery ' + esc(o.expectedDelivery) : ''}${o.tracking ? ' · ' + esc(o.carrier) + ' ' + esc(o.tracking) : ''}</p>
      ${o.status === 'CANCELLED' ? `<p class="bad"><b>This order was cancelled.</b> Refund of ${inr(o.refundTotal)} will reach you in 5–7 business days.</p>` : `<div class="tl">${steps.map((s, k) => `<div class="${k <= at ? 'done' : ''}"><i>${k <= at ? '✓' : ''}</i>${s.replace(/_/g, ' ').toLowerCase()}</div>`).join('')}</div>`}
      <h3 style="margin:18px 0 6px">Items</h3>${o.items.map(i => `<div class="oi"><img alt="" src="${art(PCACHE[i.productId] || { subCategory: '', category: '', brand: i.brand, colors: [i.color] }, i.color)}"><div><h4>${esc(i.name)}</h4><small>${esc(i.brand)}${i.size ? ' · Size ' + esc(i.size) : ''} · Qty ${i.qty} · ${inr(i.unitPrice)}</small></div><span class="badge ${i.status}">${i.status.replace(/_/g, ' ')}</span></div>`).join('')}
      <h3 style="margin:18px 0 6px">Delivery address</h3><p style="margin:0">${esc(o.address.name)}<br>${esc(o.address.line1)}${o.address.line2 ? ', ' + esc(o.address.line2) : ''}<br>${esc(o.address.city)}, ${esc(o.address.state)} ${esc(o.address.pincode)}<br>${esc(o.address.phone)}</p>
      <div class="help" style="margin:20px 0 0"><span style="font-size:28px">💬</span><div><b>Want to cancel an item or change the address?</b><small>Our assistant can do it while the order has not shipped.</small></div><button class="btn btn-primary" data-chat="Tell me about order ${o.orderId}">Ask assistant</button></div></div>`;
    bindChat();
  }

  function success(id) {
    view.innerHTML = `<div class="panel success"><div class="check">✓</div><h1>Order placed!</h1><p>Thank you. Your order <b>${esc(id)}</b> has been placed and will be delivered in about 5 days.</p>
      <p><a class="btn btn-primary" href="#/orders/${esc(id)}">View order</a> &nbsp; <a class="btn btn-ghost" href="#/">Continue shopping</a></p></div>`;
  }

  function bindChat() { $$('[data-chat]').forEach(b => b.addEventListener('click', () => window.openChat && window.openChat(b.dataset.chat))); }

  // ---------------------------------------------------------------- router
  async function route() {
    const h = location.hash.replace(/^#/, '') || '/', [path, qs] = h.split('?'), params = new URLSearchParams(qs || '');
    const parts = path.split('/').filter(Boolean);
    window.scrollTo({ top: 0 });
    $$('#catNav a').forEach(a => a.classList.toggle('on', params.get('category') === a.dataset.cat));
    document.title = 'ShopGlobal – Everything you love, delivered';
    if (!parts.length) return home();
    if (['checkout', 'orders', 'order-success'].includes(parts[0]) && !user) { location.hash = '#/login?next=' + encodeURIComponent(h); return; }
    switch (parts[0]) {
      case 'login': return user ? (location.hash = '#/') : login(params);
      case 'search': return search(params);
      case 'product': return product(parts[1]);
      case 'cart': return cartView();
      case 'checkout': return checkout();
      case 'orders': return parts[1] ? orderDetail(parts[1]) : orders();
      case 'order-success': return success(parts[1]);
      default: return home();
    }
  }

  // ---------------------------------------------------------------- header wiring
  $('#catNav').innerHTML = CATEGORIES.map(c => `<a href="#/search?category=${encodeURIComponent(c)}" data-cat="${esc(c)}">${esc(c)}</a>`).join('') + '<a href="#/search?sort=discount">🔥 Today\'s deals</a>';
  $('#searchCat').innerHTML = '<option value="">All</option>' + CATEGORIES.map(c => `<option>${esc(c)}</option>`).join('');
  $('#searchForm').addEventListener('submit', e => {
    e.preventDefault(); const p = new URLSearchParams(), q = $('#searchInput').value.trim(), c = $('#searchCat').value;
    if (q) p.set('q', q); if (c) p.set('category', c); location.hash = '#/search?' + p.toString();
  });
  document.addEventListener('click', e => { const a = e.target.closest('a[data-nolink]'); if (a) { e.preventDefault(); toast('Demo store – this link is not active'); } });
  $('#acct').addEventListener('click', e => { if (user) { e.preventDefault(); signOut(); } });
  window.addEventListener('hashchange', route);
  updateCartCount(); loadUser().then(route);
})();
