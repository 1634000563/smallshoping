// 商品数据（图片由 text_to_image 接口生成）
const PRODUCTS = [
  {
    id: 1,
    name: "无线蓝牙耳机",
    price: 199,
    category: "数码",
    desc: "主动降噪 · 30小时续航",
    img: "https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=wireless%20bluetooth%20earbuds%20product%20photography%2C%20studio%20lighting%2C%20clean%20dark%20blue%20background%2C%20minimalist&image_size=square"
  },
  {
    id: 2,
    name: "智能手机 Pro",
    price: 3999,
    category: "数码",
    desc: "6.7 英寸 OLED · 5G 双卡",
    img: "https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=modern%20smartphone%20product%20photo%2C%20studio%20lighting%2C%20dark%20blue%20background%2C%20minimalist%20commercial%20photography&image_size=square"
  },
  {
    id: 3,
    name: "轻薄笔记本电脑",
    price: 5499,
    category: "数码",
    desc: "14 英寸 2.8K 屏 · 长续航",
    img: "https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=slim%20laptop%20computer%20product%20photography%2C%20studio%20lighting%2C%20dark%20blue%20background%2C%20minimalist%20commercial%20style&image_size=square"
  },
  {
    id: 4,
    name: "智能运动手表",
    price: 899,
    category: "数码",
    desc: "心率监测 · 50 米防水",
    img: "https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=smart%20sport%20watch%20product%20photo%2C%20studio%20lighting%2C%20dark%20blue%20background%2C%20minimalist%20commercial%20photography&image_size=square"
  },
  {
    id: 5,
    name: "机械键盘 87 键",
    price: 329,
    category: "数码",
    desc: "热插拔轴体 · 三模连接",
    img: "https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=mechanical%20keyboard%20product%20photography%2C%20studio%20lighting%2C%20dark%20blue%20background%2C%20minimalist%20commercial%20style&image_size=square"
  },
  {
    id: 6,
    name: "轻便运动鞋",
    price: 269,
    category: "服饰",
    desc: "透气网面 · 缓震大底",
    img: "https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=white%20running%20sneakers%20product%20photo%2C%20studio%20lighting%2C%20dark%20blue%20background%2C%20minimalist%20commercial%20photography&image_size=square"
  },
  {
    id: 7,
    name: "城市通勤双肩包",
    price: 159,
    category: "服饰",
    desc: "15.6 英寸电脑仓 · 防泼水",
    img: "https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=modern%20black%20backpack%20product%20photography%2C%20studio%20lighting%2C%20dark%20blue%20background%2C%20minimalist%20commercial%20style&image_size=square"
  },
  {
    id: 8,
    name: "不锈钢保温杯",
    price: 89,
    category: "生活",
    desc: "316 不锈钢 · 24 小时保温",
    img: "https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=stainless%20steel%20thermos%20bottle%20product%20photo%2C%20studio%20lighting%2C%20dark%20blue%20background%2C%20minimalist%20commercial%20photography&image_size=square"
  }
];

const BANNER_IMG = "https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=online%20shopping%20ecommerce%20banner%2C%20shopping%20bags%20and%20gift%20boxes%2C%20dark%20navy%20blue%20background%20with%20red%20accent%20lighting%2C%20modern%20flat%20commercial%20illustration&image_size=landscape_16_9";
