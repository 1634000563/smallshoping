// 首页逻辑：商品渲染、分类筛选、加购
const grid = document.getElementById("product-grid");
const toast = document.getElementById("toast");
let currentCat = "all";

function renderProducts() {
  const list = currentCat === "all" ? PRODUCTS : PRODUCTS.filter(p => p.category === currentCat);
  grid.innerHTML = list.map(p => `
    <div class="card">
      <div class="card-img-wrap">
        <img class="card-img" src="${p.img}" alt="${p.name}" loading="lazy" />
        <span class="card-tag">${p.category}</span>
      </div>
      <div class="card-body">
        <div class="card-name">${p.name}</div>
        <div class="card-desc">${p.desc}</div>
        <div class="card-foot">
          <span class="card-price">${fmtPrice(p.price)}</span>
          <button class="btn btn-add" data-id="${p.id}">加入购物车</button>
        </div>
      </div>
    </div>
  `).join("");
}

// 加购
grid.addEventListener("click", e => {
  const btn = e.target.closest(".btn-add");
  if (!btn) return;
  addToCart(Number(btn.dataset.id));
  showToast("已加入购物车");
});

// 分类筛选
document.getElementById("category-chips").addEventListener("click", e => {
  const chip = e.target.closest(".chip");
  if (!chip) return;
  document.querySelectorAll(".chip").forEach(c => c.classList.remove("active"));
  chip.classList.add("active");
  currentCat = chip.dataset.cat;
  renderProducts();
});

let toastTimer;
function showToast(msg) {
  toast.textContent = msg;
  toast.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove("show"), 1800);
}

// 初始化
document.getElementById("banner-img").src = BANNER_IMG;
renderProducts();
updateCartBadge();
