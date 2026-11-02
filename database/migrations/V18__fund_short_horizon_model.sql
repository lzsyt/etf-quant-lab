-- 短期概率工件与既有中期工件共用登记；旧预测表保持20/60合同不变。
alter table fund_model drop constraint fund_model_horizon_check;
alter table fund_model add constraint fund_model_horizon_check check(horizon in (5,20,60));
