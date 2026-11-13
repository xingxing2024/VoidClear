/**
 * 玩家与世界事件边界。
 * {@link net.voidclear.v2.listener.TrashListener} 管理菜单和玩家存取；
 * {@link net.voidclear.v2.listener.PlayerTransfers} 管理会话、存档收据与拾取；
 * {@link net.voidclear.v2.listener.SweepRecovery} 核对世界中的清扫来源；
 * {@link net.voidclear.v2.listener.CareListener} 处理踩踏与爆炸保护。
 * 实体和背包变更在所属线程执行，退出后的磁盘证明在后台核验。
 */
package net.voidclear.v2.listener;
