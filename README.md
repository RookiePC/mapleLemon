# mapleLemon
for chinese maplestory V027, for any question pls contact:icelemon1024@gmail.com

已经修复内容：

1. 各职业原版3转
2. 各职业大部分技能可以使用
3. 修复普通卷轴和诅咒卷轴
4. 修复商城装备购买和穿戴
5. 修复逛图，打怪，爆物，拾取等
6. 修复任务系统

客户端下载【国内地址】：
http://pan.baidu.com/s/1c1ro4EG

感谢以下岛民的赞助：
atom
TY老后生

本项目仅供学习和研究使用。如有侵犯版权，请联系：icelemon1024@gmail.com

转载请注明出处，严禁用于商业，严禁拿去卖。

架设端步骤：

1. 安装JDK11，mariaDB11.2.x （开发者目前的开发环境）。
2. 在 MariaDB 中创建数据库（暂时记作 `maple027`)。
3. 执行 `tools/database/maplestory.sql` 以向数据库 `maple027` 中导入必要数据。
4. 解压项目中 `wz.rar` 至项目根目录。
5. 使用你的 IDE 打开此项目，进行 gradle 初始化。
6. 修改以下文件中的数据库相关配置字段：
  - `resources/META-INF/persistence.xml`
  - `src/constants/ServerConstants.java`
  - `config.ini`
7. 编译源码。
8. 开启服务端，打开登录器进入游戏。
9. 可用帐号：icelemon1314 密码：admin。

注意：
1. 不要用bat登录，直接用解压好的exe登录