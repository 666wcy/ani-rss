package ani.rss.action;

import ani.rss.commons.GsonStatic;
import ani.rss.download.Pan123;
import ani.rss.entity.Config;
import ani.rss.util.other.ConfigUtil;
import ani.rss.web.action.BaseAction;
import ani.rss.web.annotation.Auth;
import ani.rss.web.annotation.Path;
import cn.hutool.http.server.HttpServerRequest;
import cn.hutool.http.server.HttpServerResponse;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * 测试 123 网盘登录（复用缓存）
 */
@Slf4j
@Auth
@Path("/pan123TestLogin")
public class Pan123TestLoginAction implements BaseAction {
    @Override
    public void doAction(HttpServerRequest request, HttpServerResponse response) throws IOException {
        JsonObject body = getBody(JsonObject.class);
        Config config = GsonStatic.fromJson(body.getAsJsonObject("config"), Config.class);

        ConfigUtil.format(config);

        // 创建 Pan123 实例
        Pan123 pan123 = new Pan123();

        // 测试登录（使用 false 以便复用缓存的 token）
        Boolean login = pan123.login(false, config);
        if (login) {
            resultSuccessMsg("登录成功！已复用缓存的 token");
        } else {
            resultErrorMsg("登录失败，请检查账号密码");
        }
    }
}
