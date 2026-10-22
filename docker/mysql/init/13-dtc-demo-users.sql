SET NAMES utf8mb4;
-- AURORA 演示账号与地址。固定主键，可重复执行。
-- 所有账号密码：Aurora123!（演示环境，README 中注明）。
USE aurora_user;

INSERT INTO users (id, username, password, role, nickname, status) VALUES
(1, 'admin',  '$2b$12$UEh1qx6d8XAHZKWhWywaxuSezbg/tsSJ/Anw4KZDgRM340ca0xNIK', 'ADMIN', 'AURORA 运营', 1),
(2, 'linyi',  '$2b$12$UEh1qx6d8XAHZKWhWywaxuSezbg/tsSJ/Anw4KZDgRM340ca0xNIK', 'USER', '林一', 1),
(3, 'chenmo', '$2b$12$UEh1qx6d8XAHZKWhWywaxuSezbg/tsSJ/Anw4KZDgRM340ca0xNIK', 'USER', '陈墨', 1),
(4, 'zhouran','$2b$12$UEh1qx6d8XAHZKWhWywaxuSezbg/tsSJ/Anw4KZDgRM340ca0xNIK', 'USER', '周然', 1),
(5, 'wutong', '$2b$12$UEh1qx6d8XAHZKWhWywaxuSezbg/tsSJ/Anw4KZDgRM340ca0xNIK', 'USER', '吴桐', 1),
(6, 'xuzhi',  '$2b$12$UEh1qx6d8XAHZKWhWywaxuSezbg/tsSJ/Anw4KZDgRM340ca0xNIK', 'USER', '徐知', 1),
(7, 'heyu',   '$2b$12$UEh1qx6d8XAHZKWhWywaxuSezbg/tsSJ/Anw4KZDgRM340ca0xNIK', 'USER', '何遇', 1)
ON DUPLICATE KEY UPDATE password = VALUES(password), role = VALUES(role),
    nickname = VALUES(nickname), status = VALUES(status);

INSERT INTO user_address (id, user_id, receiver_name, phone, province, city, district, detail, postal_code, is_default) VALUES
(1, 1, '曾运营', '13800000001', '上海市', '上海市', '静安区', '南京西路 1266 号 8 楼', '200040', 1),
(2, 2, '林一',   '13900000002', '浙江省', '杭州市', '西湖区', '文三路 90 号 2 幢 501', '310012', 1),
(3, 3, '陈墨',   '13700000003', '广东省', '深圳市', '南山区', '科技园科苑路 15 号', '518057', 1),
(4, 4, '周然',   '13600000004', '北京市', '北京市', '朝阳区', '望京街 9 号', '100102', 1),
(5, 5, '吴桐',   '13500000005', '四川省', '成都市', '武侯区', '天府大道 1700 号', '610041', 1),
(6, 6, '徐知',   '13400000006', '江苏省', '南京市', '鼓楼区', '中山北路 100 号', '210009', 1),
(7, 7, '何遇',   '13300000007', '湖北省', '武汉市', '洪山区', '珞喻路 1037 号', '430074', 1)
ON DUPLICATE KEY UPDATE receiver_name = VALUES(receiver_name), phone = VALUES(phone),
    province = VALUES(province), city = VALUES(city), district = VALUES(district),
    detail = VALUES(detail), postal_code = VALUES(postal_code), is_default = VALUES(is_default);

USE aurora_order;

INSERT INTO coupon_template (id, title, threshold_amount, discount_amount, total, claimed, claim_start_at, claim_end_at, valid_days) VALUES
(1, '满 500 减 50', 500.00, 50.00, 100, 0, DATE_SUB(NOW(), INTERVAL 30 DAY), DATE_ADD(NOW(), INTERVAL 30 DAY), 30),
(2, '满 299 减 30', 299.00, 30.00, 100, 0, DATE_SUB(NOW(), INTERVAL 30 DAY), DATE_ADD(NOW(), INTERVAL 30 DAY), 30)
ON DUPLICATE KEY UPDATE title = VALUES(title), threshold_amount = VALUES(threshold_amount),
    discount_amount = VALUES(discount_amount), total = VALUES(total),
    claim_start_at = VALUES(claim_start_at), claim_end_at = VALUES(claim_end_at),
    valid_days = VALUES(valid_days);

INSERT INTO user_coupon (id, template_id, user_id, status, trade_id, title, threshold_amount, discount_amount, claimed_at, expire_at) VALUES
(1, 1, 2, 'UNUSED', NULL, '满 500 减 50', 500.00, 50.00, DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_ADD(NOW(), INTERVAL 28 DAY)),
(2, 2, 3, 'UNUSED', NULL, '满 299 减 30', 299.00, 30.00, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 29 DAY)),
(3, 1, 4, 'UNUSED', NULL, '满 500 减 50', 500.00, 50.00, DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_ADD(NOW(), INTERVAL 27 DAY))
ON DUPLICATE KEY UPDATE status = VALUES(status), trade_id = VALUES(trade_id),
    title = VALUES(title), threshold_amount = VALUES(threshold_amount),
    discount_amount = VALUES(discount_amount), expire_at = VALUES(expire_at);
