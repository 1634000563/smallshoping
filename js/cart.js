// 购物车页逻辑：列表渲染、数量增减、删除、结算
const cartList = document.getElementById("cart-list");
const layout = document.getElementById("cart-layout");
const emptyState = document.getElementById("empty-state");
const toast = document.getElementById("toast");

function renderCart() {
  const cart = getCart();
  const entries = Object.entries(cart);

  if (entries.length === 0) {
    layout.style.display = "none";
    emptyState.style.display = "block";
    updateCartBadge();
    return;
  }

  layout.style.display = "grid";
  emptyState.style.display = "none";

  cartList.innerHTML = entries.map(([id, qty]) => {
    const p = PRODUCTS.find(p => p.id === Number(id));
    if (!p) return "";
    return `
      <div class="cart-item">
        <img class="cart-item-img" src="${p.img}" alt="${p.name}" />
        <div class="cart-item-info">
          <div class="cart-item-name">${p.name}</div>
          <div class="cart-item-price">${fmtPrice(p.price)} / 件</div>
        </div>
        <div class="qty-control">
          <button class="qty-btn" data-id="${p.id}" data-delta="-1">−</button>
          <span class="qty-num">${qty}</span>
          <button class="qty-btn" data-id="${p.id}" data-delta="1">+</button>
        </div>
        <div class="cart-item-total">${fmtPrice(p.price * qty)}</div>
        <button class="icon-btn" data-remove="${p.id}" title="删除">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="3 6 5 6 21 6"/>
            <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>
          </svg>
        </button>
      </div>
    `;
  }).join("");

  document.getElementById("sum-count").textContent = cartCount() + " 件";
  document.getElementById("sum-total").textContent = fmtPrice(cartTotal());
  updateCartBadge();
}

// 数量增减
cartList.addEventListener("click", e => {
  const qtyBtn = e.target.closest(".qty-btn");
  if (qtyBtn) {
    const id = Number(qtyBtn.dataset.id);
    const delta = Number(qtyBtn.dataset.delta);
    const cur = getCart()[id] || 0;
    updateQty(id, cur + delta);
    renderCart();
    return;
  }
  const delBtn = e.target.closest(".icon-btn");
  if (delBtn) {
    removeFromCart(Number(delBtn.dataset.remove));
    showToast("已从购物车移除");
    renderCart();
  }
});

// 结算
document.getElementById("checkout-btn").addEventListener("click", () => {
  showToast("演示站点：结算功能暂未接入支付");
});

let toastTimer;
function showToast(msg) {
  toast.textContent = msg;
  toast.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove("show"), 1800);
}

renderCart();
