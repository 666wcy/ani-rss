package ani.rss.action;

import ani.rss.commons.GsonStatic;
import ani.rss.download.Pan123;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.util.other.ConfigUtil;
import ani.rss.web.action.BaseAction;
import ani.rss.web.annotation.Auth;
import ani.rss.web.annotation.Path;
import cn.hutool.core.io.FileUtil;
import cn.hutool.http.server.HttpServerRequest;
import cn.hutool.http.server.HttpServerResponse;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;

/**
 * 测试 123 网盘离线下载
 */
@Slf4j
@Auth
@Path("/pan123TestOfflineDownload")
public class Pan123TestAction implements BaseAction {
    @Override
    public void doAction(HttpServerRequest request, HttpServerResponse response) throws IOException {
        JsonObject body = getBody(JsonObject.class);
        Config config = GsonStatic.fromJson(body.getAsJsonObject("config"), Config.class);
        String magnet = body.get("magnet").getAsString();

        ConfigUtil.format(config);

        // 创建 Pan123 实例
        Pan123 pan123 = new Pan123();

        // 先登录（使用 false 以便复用缓存的 token）
        Boolean login = pan123.login(false, config);
        if (!login) {
            resultErrorMsg("登录失败，请检查账号密码");
            return;
        }

        // 创建临时磁力链接文件
        File tempFile = null;
        try {
            tempFile = File.createTempFile("test_magnet_", ".txt");
            FileUtil.writeUtf8String(magnet, tempFile);

            // 创建测试用的 Ani 和 Item
            Ani testAni = new Ani();
            testAni.setTitle("测试番剧");

            Item testItem = new Item();
            testItem.setTitle("测试集数");
            testItem.setReName("测试-离线下载-" + System.currentTimeMillis());

            // 使用配置中的保存路径
            String savePath = config.getDownloadPathTemplate();
            if (savePath == null || savePath.isEmpty()) {
                savePath = "/";
            }
            // 替换路径模板变量为测试值
            savePath = savePath
                    .replace("${name}", "测试番剧")
                    .replace("${year}", "2024")
                    .replace("${season}", "测试")
                    .replace("${title}", "测试番剧");

            log.info("测试保存路径: {}", savePath);

            // 测试下载
            Boolean success = pan123.download(testAni, testItem, savePath, tempFile, false);

            if (success) {
                resultSuccessMsg("测试成功！离线下载任务已提交到 123 网盘，保存路径: " + savePath);
            } else {
                resultErrorMsg("测试失败，请查看日志获取详细信息");
            }
        } catch (Exception e) {
            log.error("测试离线下载失败", e);
            resultErrorMsg("测试失败: " + e.getMessage());
        } finally {
            // 清理临时文件
            if (tempFile != null && tempFile.exists()) {
                FileUtil.del(tempFile);
            }
        }
    }
}
