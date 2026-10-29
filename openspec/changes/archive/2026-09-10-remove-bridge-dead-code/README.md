# remove-bridge-dead-code

删除 `:lark-bridge` 死机制与零引用死代码（TaskActivity 全链、HotBus/BridgeEventBus、4 个死方法、`drainAwareConnection`），零行为变化。来源：架构分析报告 P0-1/P0-2，用户已拍板删除方向。
