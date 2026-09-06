// 购物车存储逻辑（localStorage）
const CART_KEY = "smallshoping_cart";

function getCart() {
  try {
    return JSON.parse(localStorage.getItem(CART_KEY)) || {};
  } catch {
    return {};
  }
}

function saveCart(cart) {
  localStorage.setItem(CART_KEY, JSON.stringify(cart));
  updateCartBadge();
}

function addToCart(id, qty = 1) {
  const cart = getCart();
  cart[id] = (cart[id] || 0) + qty;
  saveCart(cart);
}

function updateQty(id, qty) {
  const cart = getCart();
  if (qty <= 0) delete cart[id];
  else cart[id] = qty;
  saveCart(cart);
}

function removeFromCart(id) {
  const cart = getCart();
  delete cart[id];
  saveCart(cart);
}

function cartCount() {
  return Object.values(getCart()).reduce((s, q) => s + q, 0);
}

function cartTotal() {
  const cart = getCart();
  return Object.entries(cart).reduce((sum, [id, qty]) => {
    const p = PRODUCTS.find(p => p.id === Number(id));
    return p ? sum + p.price * qty : sum;
  }, 0);
}

function updateCartBadge() {
  const badge = document.getElementById("cart-badge");
  if (badge) {
    const n = cartCount();
    badge.textContent = n;
    badge.style.display = n > 0 ? "flex" : "none";
  }
}

function fmtPrice(n) {
  return "¥" + n.toLocaleString("zh-CN");
}
