# Device / Installation / Operator Identity

## 1. V1

单设备优先，但从第一天预留：
- store_id
- installation_id
- device_id
- operator_id（可为空）

## 2. 原因

未来需要：
- 多设备同步
- 操作审计
- 云备份
- 设备更换

## 3. 设备更换

用户导出备份 → 新设备导入 → 校验 → 生成新 installation_id；历史事实保持原 store_id。

## 4. 操作员

V1 可默认一个 owner operator；未来支持员工账号。
审计日志的 actor_id 与 device_id 独立记录。
