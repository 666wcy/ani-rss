package ani.rss.download;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.TorrentsInfo;
import ani.rss.enums.TorrentsTags;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.RenameUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.CRC32;

/**
 * 123 网盘离线下载支持（基于账号密码登录）
 */
@Slf4j
public class Pan123 implements BaseDownload {
    private static final String BASE_URL = "https://www.123pan.com";
    private static final String LOGIN_API = "https://login.123pan.com/api";
    private static final String B_API = BASE_URL + "/b/api";

    // 使用静态变量缓存 token，避免频繁登录
    private static String cachedAccessToken;
    private static String cachedUsername;
    private static long tokenExpireTime = 0; // token 过期时间戳（毫秒）

    // 缓存任务 ID 和下载路径的映射关系
    private static final Map<String, String> taskPathMap = new java.util.concurrent.ConcurrentHashMap<>();
    // 缓存任务 ID 和重命名模板的映射关系
    private static final Map<String, String> taskRenameMap = new java.util.concurrent.ConcurrentHashMap<>();

    private Config config;
    private String accessToken;

    @Override
    public Boolean login(Boolean test, Config config) {
        this.config = config;
        String username = config.getDownloadToolUsername();
        String password = config.getDownloadToolPassword();

        if (StrUtil.isBlank(username) || StrUtil.isBlank(password)) {
            log.warn("123 网盘未配置完成");
            return false;
        }

        try {
            // 打印调用堆栈，帮助诊断
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder("login() 调用堆栈:\n");
            for (int i = 2; i < Math.min(8, stackTrace.length); i++) {
                sb.append("  ").append(stackTrace[i]).append("\n");
            }
            log.info(sb.toString());

            log.info("login() 调用 - test={}, cachedAccessToken={}, cachedUsername={}, username={}",
                    test, cachedAccessToken != null ? "存在" : "null", cachedUsername, username);

            // 检查缓存的 token 是否可用
            if (!test && cachedAccessToken != null && username.equals(cachedUsername)) {
                log.info("进入缓存检查分支");
                // 检查 token 是否过期（提前 5 分钟刷新）
                long now = System.currentTimeMillis();
                long timeLeft = tokenExpireTime - now;
                log.info("token 剩余时间: {} 毫秒 ({} 小时)", timeLeft, timeLeft / 1000 / 3600);

                if (now < tokenExpireTime - 5 * 60 * 1000) {
                    // 验证 token 是否有效
                    this.accessToken = cachedAccessToken;
                    log.info("开始验证 token");
                    Boolean valid = verifyToken();
                    log.info("token 验证结果: {}", valid);
                    if (valid) {
                        log.info("使用缓存的 token，无需重新登录");
                        return true;
                    } else {
                        log.info("缓存的 token 已失效，重新登录");
                        cachedAccessToken = null;
                    }
                } else {
                    log.info("缓存的 token 即将过期，重新登录");
                    cachedAccessToken = null;
                }
            } else {
                log.info("跳过缓存检查 - test={}, cachedAccessToken={}, usernameMatch={}",
                        test, cachedAccessToken != null, cachedUsername != null && username.equals(cachedUsername));
            }

            // 登录获取 token
            log.info("123 网盘登录中...");
            JsonObject body = new JsonObject();
            if (username.contains("@")) {
                // 邮箱登录
                body.addProperty("mail", username);
                body.addProperty("password", password);
                body.addProperty("type", 2);
            } else {
                // 手机号登录
                body.addProperty("passport", username);
                body.addProperty("password", password);
                body.addProperty("remember", true);
            }

            return HttpReq.post(LOGIN_API + "/user/sign_in")
                    .header("origin", BASE_URL)
                    .header("referer", BASE_URL + "/")
                    .header("platform", "web")
                    .header("app-version", "3")
                    .header("Content-Type", "application/json")
                    .body(GsonStatic.toJson(body))
                    .thenFunction(res -> {
                        HttpReq.assertStatus(res);
                        JsonObject response = GsonStatic.fromJson(res.body(), JsonObject.class);

                        int code = response.get("code").getAsInt();
                        if (code != 200) {
                            String message = response.get("message").getAsString();
                            log.error("123 网盘登录失败: {}", message);
                            return false;
                        }

                        JsonObject data = response.getAsJsonObject("data");
                        String token = data.get("token").getAsString();

                        // 缓存 token（假设 token 有效期为 24 小时）
                        this.accessToken = token;
                        cachedAccessToken = token;
                        cachedUsername = username;
                        tokenExpireTime = System.currentTimeMillis() + 24 * 60 * 60 * 1000;

                        log.info("123 网盘登录成功，token 已缓存");
                        return true;
                    });
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error("登录 123 网盘失败: {}", message, e);
            return false;
        }
    }

    @Override
    public Boolean download(Ani ani, Item item, String savePath, File torrentFile, Boolean ova) {
        String name = item.getReName();

        try {
            // 确保 token 可用
            ensureTokenAvailable();

            // 读取磁力链接
            String magnetUrl = TorrentUtil.getMagnet(torrentFile);

            log.info("123 网盘离线下载: {}", name);

            // 第一步：解析离线任务
            JsonObject resolveBody = new JsonObject();
            resolveBody.addProperty("urls", magnetUrl);

            JsonObject resolveResp = HttpReq.post(getSignedUrl(B_API + "/v2/offline_download/task/resolve"))
                    .header("authorization", "Bearer " + accessToken)
                    .header("origin", BASE_URL)
                    .header("referer", BASE_URL + "/")
                    .header("platform", "web")
                    .header("app-version", "3")
                    .header("Content-Type", "application/json")
                    .body(GsonStatic.toJson(resolveBody))
                    .thenFunction(res -> {
                        HttpReq.assertStatus(res);
                        return GsonStatic.fromJson(res.body(), JsonObject.class);
                    });

            int code = resolveResp.get("code").getAsInt();
            if (code != 0) {
                String message = resolveResp.has("message") ? resolveResp.get("message").getAsString() : "未知错误";
                log.error("123 网盘解析离线任务失败: {}", message);
                return false;
            }

            // 检查 data 字段
            if (!resolveResp.has("data") || resolveResp.get("data").isJsonNull()) {
                log.error("123 网盘解析离线任务失败: data 字段为空");
                return false;
            }

            JsonObject data = resolveResp.getAsJsonObject("data");

            // 检查 list 字段
            if (!data.has("list") || data.get("list").isJsonNull()) {
                log.error("123 网盘解析离线任务失败: list 字段为空");
                return false;
            }

            JsonArray list = data.getAsJsonArray("list");
            if (list == null || list.size() == 0) {
                log.error("123 网盘解析离线任务失败: 空响应");
                return false;
            }

            JsonObject taskInfo = list.get(0).getAsJsonObject();
            int result = taskInfo.get("result").getAsInt();
            if (result != 0) {
                String errMsg = taskInfo.has("err_msg") ? taskInfo.get("err_msg").getAsString() : "解析失败";
                log.error("123 网盘解析离线任务失败: {}, 完整响应: {}", errMsg, taskInfo);
                return false;
            }

            long resourceId = taskInfo.get("id").getAsLong();

            // 检查 files 字段是否存在且不为 null
            if (!taskInfo.has("files") || taskInfo.get("files").isJsonNull()) {
                log.error("123 网盘解析离线任务失败: files 字段为空或不存在");
                return false;
            }

            JsonArray files = taskInfo.getAsJsonArray("files");
            if (files == null || files.size() == 0) {
                log.error("123 网盘解析离线任务失败: 文件列表为空");
                return false;
            }

            // 收集所有文件 ID
            List<Long> selectFileIds = new ArrayList<>();
            for (JsonElement fileElement : files) {
                JsonObject file = fileElement.getAsJsonObject();
                long fileId = file.get("id").getAsLong();
                if (fileId > 0) {
                    selectFileIds.add(fileId);
                }
            }

            if (selectFileIds.isEmpty()) {
                log.error("123 网盘解析离线任务失败: 文件列表为空");
                return false;
            }

            // 第二步：提交离线任务
            JsonObject submitBody = new JsonObject();
            JsonArray resourceList = new JsonArray();
            JsonObject resource = new JsonObject();
            resource.addProperty("resource_id", resourceId);
            JsonArray selectFileIdArray = new JsonArray();
            for (Long fileId : selectFileIds) {
                selectFileIdArray.add(fileId);
            }
            resource.add("select_file_id", selectFileIdArray);
            resourceList.add(resource);
            submitBody.add("resource_list", resourceList);

            // 获取或创建保存目录
            long uploadDirId = getOrCreateFolder(savePath);
            submitBody.addProperty("upload_dir", uploadDirId);

            JsonObject submitResp = HttpReq.post(getSignedUrl(B_API + "/v2/offline_download/task/submit"))
                    .header("authorization", "Bearer " + accessToken)
                    .header("origin", BASE_URL)
                    .header("referer", BASE_URL + "/")
                    .header("platform", "web")
                    .header("app-version", "3")
                    .header("Content-Type", "application/json")
                    .body(GsonStatic.toJson(submitBody))
                    .thenFunction(res -> {
                        HttpReq.assertStatus(res);
                        return GsonStatic.fromJson(res.body(), JsonObject.class);
                    });

            code = submitResp.get("code").getAsInt();
            if (code != 0) {
                String message = submitResp.get("message").getAsString();
                log.error("123 网盘提交离线任务失败: {}", message);
                return false;
            }

            // 从响应中获取任务 ID，并保存路径映射
            JsonObject submitData = submitResp.getAsJsonObject("data");
            log.debug("123 网盘提交响应 data: {}", submitData);

            boolean taskIdSaved = false;
            if (submitData != null && submitData.has("task_list") && !submitData.get("task_list").isJsonNull()) {
                JsonArray taskList = submitData.getAsJsonArray("task_list");
                log.debug("123 网盘提交响应 task_list 大小: {}", taskList.size());
                if (taskList.size() > 0) {
                    JsonObject firstTask = taskList.get(0).getAsJsonObject();
                    if (firstTask.has("task_id")) {
                        String taskId = String.valueOf(firstTask.get("task_id").getAsLong());
                        // 保存任务 ID 和下载路径的映射
                        taskPathMap.put(taskId, savePath);
                        // 保存任务 ID 和重命名模板的映射
                        taskRenameMap.put(taskId, name);
                        log.info("保存任务映射: taskId={}, path={}, reName={}", taskId, savePath, name);
                        taskIdSaved = true;
                    } else {
                        log.warn("123 网盘提交响应中没有 task_id 字段");
                    }
                } else {
                    log.warn("123 网盘提交响应 task_list 为空");
                }
            } else {
                log.warn("123 网盘提交响应中没有 task_list 字段或为 null");
            }

            // 如果没有从响应中获取到任务 ID，尝试通过任务列表 API 查找
            if (!taskIdSaved) {
                try {
                    log.debug("尝试通过任务列表 API 查找刚创建的任务");
                    Thread.sleep(2000); // 等待 2 秒让任务出现在列表中

                    // 获取当前所有任务
                    List<TorrentsInfo> torrents = getTorrentsInfos();

                    // 为所有没有路径映射的任务设置路径
                    // 假设刚创建的任务是最新的，且没有路径映射
                    for (TorrentsInfo torrent : torrents) {
                        String taskId = torrent.getHash(); // hash 字段存储的是 task_id
                        if (!taskPathMap.containsKey(taskId)) {
                            // 这是一个新任务，保存路径和重命名映射
                            taskPathMap.put(taskId, savePath);
                            taskRenameMap.put(taskId, name);
                            log.info("通过任务列表为新任务保存映射: taskId={}, name={}, path={}, reName={}",
                                    taskId, torrent.getName(), savePath, name);
                            taskIdSaved = true;
                            break; // 只处理第一个新任务
                        }
                    }

                    if (!taskIdSaved) {
                        log.warn("无法通过任务列表找到新创建的任务");
                    }
                } catch (Exception e) {
                    log.warn("通过任务列表查找任务 ID 失败: {}", e.getMessage());
                }
            }

            log.info("123 网盘离线下载任务创建成功: {}", name);
            return true;
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error("123 网盘离线下载失败: {}", message, e);
            return false;
        }
    }

    @Override
    public List<TorrentsInfo> getTorrentsInfos() {
        try {
            // 确保 token 可用
            ensureTokenAvailable();

            // 获取离线任务列表
            JsonObject body = new JsonObject();
            body.addProperty("current_page", 1);
            body.addProperty("page_size", 100);
            JsonArray statusArr = new JsonArray();
            statusArr.add(0); // 等待中
            statusArr.add(1); // 下载中
            statusArr.add(2); // 已完成
            statusArr.add(3); // 失败
            body.add("status_arr", statusArr);

            return HttpReq.post(getSignedUrl(B_API + "/offline_download/task/list"))
                    .header("authorization", "Bearer " + accessToken)
                    .header("origin", BASE_URL)
                    .header("referer", BASE_URL + "/")
                    .header("platform", "web")
                    .header("app-version", "3")
                    .header("Content-Type", "application/json")
                    .body(GsonStatic.toJson(body))
                    .thenFunction(res -> {
                        HttpReq.assertStatus(res);
                        JsonObject response = GsonStatic.fromJson(res.body(), JsonObject.class);

                        int code = response.get("code").getAsInt();
                        if (code != 0) {
                            log.error("获取 123 网盘离线任务列表失败");
                            return new ArrayList<>();
                        }

                        JsonObject data = response.getAsJsonObject("data");
                        if (data == null || !data.has("list") || data.get("list").isJsonNull()) {
                            log.debug("123 网盘离线任务列表为空");
                            return new ArrayList<>();
                        }

                        JsonArray taskList = data.getAsJsonArray("list");

                        List<TorrentsInfo> list = new ArrayList<>();
                        for (JsonElement element : taskList) {
                            JsonObject task = element.getAsJsonObject();

                            String taskId = String.valueOf(task.get("task_id").getAsLong());

                            TorrentsInfo info = new TorrentsInfo();
                            info.setName(task.get("name").getAsString());
                            info.setHash(taskId);
                            info.setSize(task.get("size").getAsLong());

                            // 从缓存中获取下载路径
                            String downloadPath = taskPathMap.get(taskId);
                            if (downloadPath != null) {
                                info.setDownloadDir(downloadPath);
                            } else {
                                // 对于旧任务（应用重启前下载的），没有路径映射
                                // 设置为空字符串，重命名时会跳过
                                info.setDownloadDir("");
                                log.debug("123 网盘任务 {} 没有路径映射（可能是旧任务）: {}", taskId, task.get("name").getAsString());
                            }

                            // 状态映射: 0-等待 1-下载中 2-完成 3-失败
                            int status = task.get("status").getAsInt();
                            switch (status) {
                                case 0:
                                    info.setState(TorrentsInfo.State.queuedDL);
                                    info.setProgress(0.0);
                                    break;
                                case 1:
                                    info.setState(TorrentsInfo.State.downloading);
                                    double progress = task.get("progress").getAsDouble();
                                    info.setProgress(progress * 100);
                                    break;
                                case 2:
                                    info.setState(TorrentsInfo.State.pausedUP);
                                    info.setProgress(100.0);
                                    break;
                                case 3:
                                    info.setState(TorrentsInfo.State.error);
                                    info.setProgress(0.0);
                                    break;
                            }

                            info.setTags(List.of(TorrentsTags.ANI_RSS.getValue()));
                            list.add(info);
                        }
                        return list;
                    });
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    @Override
    public Boolean delete(TorrentsInfo torrentsInfo, Boolean deleteFiles) {
        String taskId = torrentsInfo.getHash();
        String name = torrentsInfo.getName();

        try {
            // 确保 token 可用
            ensureTokenAvailable();

            log.info("删除 123 网盘离线任务: {}", name);

            JsonObject body = new JsonObject();
            JsonArray taskIds = new JsonArray();
            taskIds.add(Long.parseLong(taskId));
            body.add("task_ids", taskIds);

            return HttpReq.post(getSignedUrl(B_API + "/offline_download/task/delete"))
                    .header("authorization", "Bearer " + accessToken)
                    .header("origin", BASE_URL)
                    .header("referer", BASE_URL + "/")
                    .header("platform", "web")
                    .header("app-version", "3")
                    .header("Content-Type", "application/json")
                    .body(GsonStatic.toJson(body))
                    .thenFunction(res -> {
                        HttpReq.assertStatus(res);
                        JsonObject response = GsonStatic.fromJson(res.body(), JsonObject.class);

                        if (response.get("code").getAsInt() == 0) {
                            log.info("123 网盘离线任务已删除: {}", name);
                            return true;
                        }

                        log.error("删除 123 网盘离线任务失败: {}", name);
                        return false;
                    });
        } catch (Exception e) {
            log.error("删除离线任务失败: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public void rename(TorrentsInfo torrentsInfo) {
        String originalFileName = torrentsInfo.getName();
        String taskId = torrentsInfo.getHash();
        TorrentsInfo.State state = torrentsInfo.getState();

        // 只对已完成的任务进行重命名
        if (state != TorrentsInfo.State.pausedUP) {
            log.debug("123 网盘任务未完成，跳过重命名: {}", originalFileName);
            return;
        }

        try {
            // 确保 token 可用
            ensureTokenAvailable();

            // 通过下载路径查找对应的番剧
            String downloadDir = torrentsInfo.getDownloadDir();

            // 如果下载路径为空，说明是旧任务（应用重启前下载的）
            if (StrUtil.isBlank(downloadDir)) {
                log.debug("123 网盘跳过旧任务重命名（路径映射丢失）: {}", originalFileName);
                return;
            }

            log.debug("123 网盘尝试查找番剧，下载路径: {}, 文件名: {}", downloadDir, originalFileName);

            // 尝试从缓存中获取重命名模板
            String reName = taskRenameMap.get(taskId);
            if (reName != null) {
                log.debug("从缓存中获取重命名模板: {}", reName);
            } else {
                // 如果缓存中没有，使用 RenameUtil 生成
                log.debug("缓存中没有重命名模板，尝试使用 RenameUtil 生成");

                Optional<ani.rss.entity.Ani> aniOpt = ani.rss.service.DownloadService.findAniByDownloadPath(torrentsInfo);
                if (aniOpt.isEmpty()) {
                    log.warn("未能获取番剧对象（下载路径: {}）: {}", downloadDir, originalFileName);
                    return;
                }

                ani.rss.entity.Ani ani = aniOpt.get();

                // 使用原项目的 RenameUtil 生成重命名模板
                // 创建临时 Item 对象，设置原始文件名作为标题
                Item tempItem = new Item();
                tempItem.setTitle(originalFileName); // RenameUtil 会从标题中提取集数信息

                // 从文件名中提取字幕组信息
                // 格式通常是 [字幕组] 或 【字幕组】
                String subgroup = extractSubgroup(originalFileName);
                if (subgroup != null) {
                    tempItem.setSubgroup(subgroup);
                    log.debug("从文件名中提取字幕组: {}", subgroup);
                }

                // 使用 RenameUtil 生成重命名模板（会应用配置的模板和规则）
                Boolean renameSuccess = RenameUtil.rename(ani, tempItem);
                if (!renameSuccess || tempItem.getReName() == null) {
                    log.debug("无法使用 RenameUtil 生成重命名模板: {}", originalFileName);
                    return;
                }

                reName = tempItem.getReName();
                log.debug("使用 RenameUtil 生成重命名模板: {}", reName);
            }

            // 获取离线任务详情，找到所有相关文件
            List<FileRenameInfo> allFiles = getAllFilesForRename(taskId);
            if (allFiles == null || allFiles.isEmpty()) {
                log.warn("123 网盘重命名失败: 未找到文件信息，任务: {}", originalFileName);
                return;
            }

            log.debug("找到 {} 个文件需要处理", allFiles.size());

            // 处理每个文件
            for (FileRenameInfo renameInfo : allFiles) {
                final String currentFileName = renameInfo.currentFileName;
                final Long fileId = renameInfo.fileId;
                final Long targetDirId = renameInfo.targetDirId;
                final Long originalParentDirId = renameInfo.originalParentDirId;

                // 使用 getFileReName 方法生成新文件名（应用匹配规则）
                String newFileName = getFileReName(currentFileName, reName);
                final String finalNewFileName = newFileName;

                // 如果文件名相同且在正确的目录，无需操作
                if (currentFileName.equals(finalNewFileName) && renameInfo.isInTargetDir) {
                    log.debug("123 网盘文件已正确，无需重命名或移动: {}", currentFileName);
                    continue;
                }

                // 如果文件不在目标目录，需要先移动
                if (!renameInfo.isInTargetDir && targetDirId != null) {
                    log.info("123 网盘移动文件到目标目录: {} -> 目录ID: {}", currentFileName, targetDirId);
                    Boolean moved = moveFile(fileId, targetDirId);
                    if (!moved) {
                        log.error("123 网盘移动文件失败: {}", currentFileName);
                        continue;
                    }

                    // 移动成功后，检查原文件夹是否为空，如果为空则删除
                    if (originalParentDirId != null && !originalParentDirId.equals(targetDirId)) {
                        deleteEmptyFolder(originalParentDirId);
                    }
                }

                // 如果文件名不同，需要重命名
                if (!currentFileName.equals(finalNewFileName)) {
                    // 调用重命名 API
                    JsonObject body = new JsonObject();
                    body.addProperty("driveId", 0);
                    body.addProperty("fileId", fileId);
                    body.addProperty("fileName", finalNewFileName);

                    Boolean success = HttpReq.post(getSignedUrl(B_API + "/file/rename"))
                            .header("authorization", "Bearer " + accessToken)
                            .header("origin", BASE_URL)
                            .header("referer", BASE_URL + "/")
                            .header("platform", "web")
                            .header("app-version", "3")
                            .header("Content-Type", "application/json")
                            .body(GsonStatic.toJson(body))
                            .thenFunction(res -> {
                                HttpReq.assertStatus(res);
                                JsonObject response = GsonStatic.fromJson(res.body(), JsonObject.class);

                                if (response.get("code").getAsInt() == 0) {
                                    log.info("123 网盘重命名成功: {} -> {}", currentFileName, finalNewFileName);
                                    return true;
                                }

                                String message = response.has("message") ? response.get("message").getAsString() : "未知错误";
                                log.error("123 网盘重命名失败: {}", message);
                                return false;
                            });

                    if (success) {
                        torrentsInfo.setName(finalNewFileName);
                    }
                }
            }

            // 重命名流程完成，从缓存中移除该任务的映射，避免重复处理
            taskPathMap.remove(taskId);
            taskRenameMap.remove(taskId);
            log.debug("重命名流程完成，已从缓存中移除任务映射: taskId={}", taskId);

            // 删除离线任务记录
            deleteOfflineTask(taskId);
        } catch (Exception e) {
            log.error("123 网盘重命名失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 从文件名中提取字幕组信息
     * 例如：[LoliHouse] 番剧名 - 01.mkv -> LoliHouse
     * @param fileName 文件名
     * @return 字幕组名称，如果未找到则返回 null
     */
    private String extractSubgroup(String fileName) {
        try {
            // 匹配 [字幕组] 或 【字幕组】 格式
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("^[\\[【]([^\\]】]+)[\\]】]");
            java.util.regex.Matcher matcher = pattern.matcher(fileName);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
            return null;
        } catch (Exception e) {
            log.warn("提取字幕组失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 移动文件到指定目录
     * @param fileId 文件 ID
     * @param targetDirId 目标目录 ID
     * @return 是否成功
     */
    private Boolean moveFile(long fileId, long targetDirId) {
        try {
            // 构造请求体，fileIdList 是对象数组，每个对象包含 FileId 字段
            JsonObject body = new JsonObject();
            JsonArray fileIdArray = new JsonArray();
            JsonObject fileIdObj = new JsonObject();
            fileIdObj.addProperty("FileId", fileId);
            fileIdArray.add(fileIdObj);
            body.add("fileIdList", fileIdArray);
            body.addProperty("parentFileId", targetDirId);

            log.debug("移动文件请求参数: {}", body.toString());

            return HttpReq.post(getSignedUrl(B_API + "/file/mod_pid"))
                    .header("authorization", "Bearer " + accessToken)
                    .header("origin", BASE_URL)
                    .header("referer", BASE_URL + "/")
                    .header("platform", "web")
                    .header("app-version", "3")
                    .header("Content-Type", "application/json")
                    .body(GsonStatic.toJson(body))
                    .thenFunction(res -> {
                        HttpReq.assertStatus(res);
                        JsonObject response = GsonStatic.fromJson(res.body(), JsonObject.class);

                        log.debug("移动文件响应: {}", res.body());

                        int code = response.get("code").getAsInt();
                        if (code == 0) {
                            log.info("123 网盘移动文件成功，文件ID: {} -> 目录ID: {}", fileId, targetDirId);
                            return true;
                        }

                        String message = response.has("message") ? response.get("message").getAsString() : "未知错误";
                        log.error("123 网盘移动文件失败: {}", message);
                        return false;
                    });
        } catch (Exception e) {
            log.error("123 网盘移动文件失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 删除空文件夹
     * @param folderId 文件夹 ID
     */
    private void deleteEmptyFolder(long folderId) {
        try {
            // 等待一小段时间，确保文件移动操作已完全同步
            Thread.sleep(500);

            // 先检查文件夹是否为空
            Boolean isEmpty = HttpReq.get(getSignedUrl(B_API + "/file/list/new"))
                    .header("authorization", "Bearer " + accessToken)
                    .header("origin", BASE_URL)
                    .header("referer", BASE_URL + "/")
                    .header("platform", "web")
                    .header("app-version", "3")
                    .form("driveId", 0)
                    .form("limit", 10)
                    .form("parentFileId", folderId)
                    .form("trashed", false)
                    .form("orderBy", "file_id")
                    .form("orderDirection", "desc")
                    .thenFunction(res -> {
                        HttpReq.assertStatus(res);
                        JsonObject response = GsonStatic.fromJson(res.body(), JsonObject.class);

                        int code = response.get("code").getAsInt();
                        if (code != 0) {
                            String message = response.has("message") ? response.get("message").getAsString() : "未知错误";
                            log.warn("检查文件夹是否为空失败，文件夹ID: {}, code: {}, message: {}", folderId, code, message);
                            return false;
                        }

                        JsonObject data = response.getAsJsonObject("data");
                        if (data == null || !data.has("InfoList")) {
                            log.debug("文件夹没有 InfoList，认为是空的，文件夹ID: {}", folderId);
                            return true; // 没有文件列表，认为是空的
                        }

                        JsonArray infoList = data.getAsJsonArray("InfoList");
                        boolean empty = infoList.size() == 0;
                        log.debug("文件夹检查结果，文件夹ID: {}, 文件数量: {}, 是否为空: {}", folderId, infoList.size(), empty);
                        return empty; // 文件列表为空
                    });

            if (isEmpty == null || !isEmpty) {
                log.debug("文件夹不为空或检查失败，跳过删除，文件夹ID: {}", folderId);
                return;
            }

            // 文件夹为空，删除它
            // 参考 OpenList: https://github.com/OpenListTeam/OpenList/tree/main/drivers/123
            JsonObject body = new JsonObject();
            body.addProperty("driveId", 0);
            body.addProperty("operation", true); // true 表示移到回收站

            // 构造 fileTrashInfoList 数组
            JsonArray fileTrashInfoList = new JsonArray();
            JsonObject fileInfo = new JsonObject();
            fileInfo.addProperty("FileId", folderId);
            fileTrashInfoList.add(fileInfo);
            body.add("fileTrashInfoList", fileTrashInfoList);

            Boolean deleted = HttpReq.post(getSignedUrl(B_API + "/file/trash"))
                    .header("authorization", "Bearer " + accessToken)
                    .header("origin", BASE_URL)
                    .header("referer", BASE_URL + "/")
                    .header("platform", "web")
                    .header("app-version", "3")
                    .header("Content-Type", "application/json")
                    .body(GsonStatic.toJson(body))
                    .thenFunction(res -> {
                        HttpReq.assertStatus(res);
                        JsonObject response = GsonStatic.fromJson(res.body(), JsonObject.class);

                        if (response.get("code").getAsInt() == 0) {
                            log.info("123 网盘删除空文件夹成功，文件夹ID: {}", folderId);
                            return true;
                        }

                        String message = response.has("message") ? response.get("message").getAsString() : "未知错误";
                        log.warn("123 网盘删除空文件夹失败，文件夹ID: {}, 错误: {}", folderId, message);
                        return false;
                    });

        } catch (Exception e) {
            log.warn("删除空文件夹失败，文件夹ID: {}, 错误: {}", folderId, e.getMessage());
        }
    }

    /**
     * 删除离线任务记录
     * @param taskId 任务 ID
     */
    private void deleteOfflineTask(String taskId) {
        try {
            JsonObject body = new JsonObject();
            JsonArray taskIds = new JsonArray();
            taskIds.add(Long.parseLong(taskId));
            body.add("task_ids", taskIds);

            Boolean deleted = HttpReq.post(getSignedUrl(B_API + "/offline_download/task/delete"))
                    .header("authorization", "Bearer " + accessToken)
                    .header("origin", BASE_URL)
                    .header("referer", BASE_URL + "/")
                    .header("platform", "web")
                    .header("app-version", "3")
                    .header("Content-Type", "application/json")
                    .body(GsonStatic.toJson(body))
                    .thenFunction(res -> {
                        HttpReq.assertStatus(res);
                        JsonObject response = GsonStatic.fromJson(res.body(), JsonObject.class);

                        if (response.get("code").getAsInt() == 0) {
                            log.info("123 网盘删除离线任务成功，任务ID: {}", taskId);
                            return true;
                        }

                        String message = response.has("message") ? response.get("message").getAsString() : "未知错误";
                        log.warn("123 网盘删除离线任务失败，任务ID: {}, 错误: {}", taskId, message);
                        return false;
                    });

        } catch (Exception e) {
            log.warn("删除离线任务失败，任务ID: {}, 错误: {}", taskId, e.getMessage());
        }
    }

    /**
     * 文件重命名信息
     */
    private static class FileRenameInfo {
        long fileId;
        String currentFileName;
        Long targetDirId;  // 目标目录 ID（upload_idr）
        Long originalParentDirId;  // 原文件所在的父目录 ID
        boolean isInTargetDir;  // 文件是否已在目标目录

        FileRenameInfo(long fileId, String currentFileName) {
            this.fileId = fileId;
            this.currentFileName = currentFileName;
            this.targetDirId = null;
            this.originalParentDirId = null;
            this.isInTargetDir = false;
        }

        FileRenameInfo(long fileId, String currentFileName, Long targetDirId, boolean isInTargetDir) {
            this.fileId = fileId;
            this.currentFileName = currentFileName;
            this.targetDirId = targetDirId;
            this.originalParentDirId = null;
            this.isInTargetDir = isInTargetDir;
        }

        FileRenameInfo(long fileId, String currentFileName, Long targetDirId, Long originalParentDirId, boolean isInTargetDir) {
            this.fileId = fileId;
            this.currentFileName = currentFileName;
            this.targetDirId = targetDirId;
            this.originalParentDirId = originalParentDirId;
            this.isInTargetDir = isInTargetDir;
        }
    }

    @Override
    public Boolean addTags(TorrentsInfo torrentsInfo, String tags) {
        log.debug("123 网盘不支持标签功能");
        return false;
    }

    @Override
    public void updateTrackers(Set<String> trackers) {
        log.debug("123 网盘不需要 Trackers 配置");
    }

    @Override
    public void setSavePath(TorrentsInfo torrentsInfo, String path) {
        log.debug("123 网盘暂不支持修改保存路径: {}", torrentsInfo.getName());
    }

    /**
     * 验证 token 是否有效
     */
    private Boolean verifyToken() {
        try {
            return HttpReq.get(getSignedUrl(B_API + "/user/info"))
                    .header("authorization", "Bearer " + accessToken)
                    .header("origin", BASE_URL)
                    .header("referer", BASE_URL + "/")
                    .header("platform", "web")
                    .header("app-version", "3")
                    .thenFunction(res -> {
                        if (!res.isOk()) {
                            return false;
                        }
                        JsonObject response = GsonStatic.fromJson(res.body(), JsonObject.class);
                        return response.get("code").getAsInt() == 0;
                    });
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 生成带签名的 URL
     * 参考 OpenList 的实现
     */
    private String getSignedUrl(String url) {
        try {
            String path = url.substring(url.indexOf("/api"));

            // 生成签名参数
            long timestamp = Instant.now().getEpochSecond();
            int random = (int) (Math.random() * 10000000);

            // 生成时间签名
            String timeStr = Instant.ofEpochSecond(timestamp)
                    .atZone(ZoneId.of("Asia/Shanghai"))
                    .format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));

            char[] table = {'a', 'd', 'e', 'f', 'g', 'h', 'l', 'm', 'y', 'i', 'j', 'n', 'o', 'p', 'k', 'q', 'r', 's', 't', 'u', 'b', 'c', 'v', 'w', 's', 'z'};
            StringBuilder timeSign = new StringBuilder();
            for (char c : timeStr.toCharArray()) {
                timeSign.append(table[c - '0']);
            }

            CRC32 crc32 = new CRC32();
            crc32.update(timeSign.toString().getBytes(StandardCharsets.UTF_8));
            String timeSignValue = String.valueOf(crc32.getValue());

            // 生成数据签名
            String data = timestamp + "|" + random + "|" + path + "|web|3|" + timeSignValue;
            crc32.reset();
            crc32.update(data.getBytes(StandardCharsets.UTF_8));
            String dataSign = String.valueOf(crc32.getValue());

            String signParam = timeSignValue + "=" + timestamp + "-" + random + "-" + dataSign;

            return url + "?" + signParam;
        } catch (Exception e) {
            log.warn("生成签名 URL 失败: {}", e.getMessage());
            return url;
        }
    }

    /**
     * 获取或创建文件夹
     * @param path 文件夹路径，如 "/动漫/2024" 或空字符串表示根目录
     * @return 文件夹 ID
     */
    private long getOrCreateFolder(String path) {
        try {
            // 如果路径为空或根目录，返回 0
            if (StrUtil.isBlank(path) || "/".equals(path)) {
                return 0;
            }

            // 移除开头和结尾的斜杠
            path = path.replaceAll("^/+|/+$", "");
            if (StrUtil.isBlank(path)) {
                return 0;
            }

            // 分割路径
            String[] folders = path.split("/");
            long parentId = 0;

            // 逐级查找或创建文件夹
            for (String folderName : folders) {
                if (StrUtil.isBlank(folderName)) {
                    continue;
                }

                // 查找文件夹
                Long folderId = findFolder(parentId, folderName);
                if (folderId != null) {
                    parentId = folderId;
                } else {
                    // 创建文件夹
                    parentId = createFolder(parentId, folderName);
                }
            }

            return parentId;
        } catch (Exception e) {
            log.warn("获取或创建文件夹失败: {}, 使用根目录", e.getMessage());
            return 0;
        }
    }

    /**
     * 查找文件夹
     * @param parentId 父文件夹 ID
     * @param folderName 文件夹名称
     * @return 文件夹 ID，如果不存在返回 null
     */
    private Long findFolder(long parentId, String folderName) {
        try {
            // 确保 token 可用
            ensureTokenAvailable();

            return HttpReq.get(getSignedUrl(B_API + "/file/list/new"))
                    .header("authorization", "Bearer " + accessToken)
                    .header("origin", BASE_URL)
                    .header("referer", BASE_URL + "/")
                    .header("platform", "web")
                    .header("app-version", "3")
                    .form("driveId", 0)
                    .form("limit", 100)
                    .form("parentFileId", parentId)
                    .form("trashed", false)
                    .form("orderBy", "file_id")
                    .form("orderDirection", "desc")
                    .thenFunction(res -> {
                        HttpReq.assertStatus(res);
                        JsonObject response = GsonStatic.fromJson(res.body(), JsonObject.class);

                        log.debug("查找文件夹响应 (parentId={}, folderName={}): {}", parentId, folderName, res.body());

                        if (response.get("code").getAsInt() != 0) {
                            log.warn("查找文件夹失败: code={}", response.get("code").getAsInt());
                            return null;
                        }

                        JsonObject data = response.getAsJsonObject("data");
                        if (data == null || !data.has("InfoList")) {
                            log.warn("响应数据中没有 InfoList");
                            return null;
                        }

                        JsonArray infoList = data.getAsJsonArray("InfoList");
                        log.debug("文件列表大小: {}", infoList.size());

                        for (JsonElement element : infoList) {
                            JsonObject file = element.getAsJsonObject();
                            String fileName = file.get("FileName").getAsString();
                            int type = file.get("Type").getAsInt();
                            log.debug("文件: {} (Type: {})", fileName, type);

                            // Type: 0-文件 1-文件夹
                            if (type == 1 && fileName.equals(folderName)) {
                                long fileId = file.get("FileId").getAsLong();
                                log.info("找到文件夹: {} (ID: {})", folderName, fileId);
                                return fileId;
                            }
                        }

                        log.debug("未找到文件夹: {}", folderName);
                        return null;
                    });
        } catch (Exception e) {
            log.warn("查找文件夹失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 创建文件夹
     * @param parentId 父文件夹 ID
     * @param folderName 文件夹名称
     * @return 新创建的文件夹 ID
     */
    private long createFolder(long parentId, String folderName) {
        try {
            // 确保 token 可用
            ensureTokenAvailable();

            JsonObject body = new JsonObject();
            body.addProperty("driveId", 0);
            body.addProperty("etag", "");
            body.addProperty("fileName", folderName);
            body.addProperty("parentFileId", parentId);
            body.addProperty("size", 0);
            body.addProperty("type", 1); // 1 表示文件夹

            Long fileId = HttpReq.post(getSignedUrl(B_API + "/file/upload_request"))
                    .header("authorization", "Bearer " + accessToken)
                    .header("origin", BASE_URL)
                    .header("referer", BASE_URL + "/")
                    .header("platform", "web")
                    .header("app-version", "3")
                    .header("Content-Type", "application/json")
                    .body(GsonStatic.toJson(body))
                    .thenFunction(res -> {
                        HttpReq.assertStatus(res);
                        JsonObject response = GsonStatic.fromJson(res.body(), JsonObject.class);

                        log.debug("创建文件夹响应: {}", res.body());

                        if (response.get("code").getAsInt() != 0) {
                            throw new RuntimeException("创建文件夹失败: " + response.get("message").getAsString());
                        }

                        JsonObject data = response.getAsJsonObject("data");
                        if (data == null || data.isJsonNull()) {
                            log.warn("创建文件夹响应数据为空，将通过查询获取 ID");
                            return null;
                        }

                        if (!data.has("FileId")) {
                            log.warn("响应数据中没有 FileId 字段，将通过查询获取 ID");
                            return null;
                        }

                        long id = data.get("FileId").getAsLong();
                        if (id == 0) {
                            log.warn("FileId 为 0，将通过查询获取 ID");
                            return null;
                        }

                        log.info("创建文件夹成功: {} (ID: {})", folderName, id);
                        return id;
                    });

            // 如果创建接口没有返回有效的 FileId，通过查询获取
            if (fileId == null || fileId == 0) {
                log.info("通过查询获取新创建文件夹的 ID: {}", folderName);
                // 等待一小段时间，确保文件夹创建完成
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                fileId = findFolder(parentId, folderName);
                if (fileId == null) {
                    throw new RuntimeException("创建文件夹后无法查询到: " + folderName);
                }
                log.info("查询到新创建的文件夹: {} (ID: {})", folderName, fileId);
            }

            return fileId;
        } catch (Exception e) {
            log.error("创建文件夹失败: {}", e.getMessage(), e);
            throw new RuntimeException("创建文件夹失败: " + e.getMessage());
        }
    }

    /**
     * 确保 token 可用
     * 如果实例的 accessToken 为空，从缓存中恢复
     */
    private void ensureTokenAvailable() {
        if (accessToken == null && cachedAccessToken != null) {
            accessToken = cachedAccessToken;
            log.debug("从缓存恢复 token");
        }
    }

    /**
     * 从离线任务中获取文件重命名信息
     * @param taskId 离线任务 ID
     * @return 文件重命名信息，如果未找到返回 null
     */
    /**
     * 获取所有需要重命名的文件
     * @param taskId 任务 ID
     * @return 文件列表
     */
    private List<FileRenameInfo> getAllFilesForRename(String taskId) {
        try {
            // 获取离线任务详情
            JsonObject body = new JsonObject();
            body.addProperty("current_page", 1);
            body.addProperty("page_size", 100);
            JsonArray statusArr = new JsonArray();
            statusArr.add(2); // 只查询已完成的任务
            body.add("status_arr", statusArr);

            return HttpReq.post(getSignedUrl(B_API + "/offline_download/task/list"))
                    .header("authorization", "Bearer " + accessToken)
                    .header("origin", BASE_URL)
                    .header("referer", BASE_URL + "/")
                    .header("platform", "web")
                    .header("app-version", "3")
                    .header("Content-Type", "application/json")
                    .body(GsonStatic.toJson(body))
                    .thenFunction(res -> {
                        HttpReq.assertStatus(res);
                        JsonObject response = GsonStatic.fromJson(res.body(), JsonObject.class);

                        if (response.get("code").getAsInt() != 0) {
                            log.error("获取离线任务列表失败");
                            return null;
                        }

                        JsonObject data = response.getAsJsonObject("data");
                        if (data == null || !data.has("list") || data.get("list").isJsonNull()) {
                            log.debug("123 网盘离线任务列表为空");
                            return null;
                        }

                        JsonArray list = data.getAsJsonArray("list");
                        for (JsonElement element : list) {
                            JsonObject task = element.getAsJsonObject();
                            String currentTaskId = String.valueOf(task.get("task_id").getAsLong());

                            if (currentTaskId.equals(taskId)) {
                                log.debug("找到离线任务，完整信息: {}", task);

                                // 获取目标目录 ID
                                Long targetDirId = null;
                                if (task.has("upload_idr") && !task.get("upload_idr").isJsonNull()) {
                                    targetDirId = task.get("upload_idr").getAsLong();
                                } else if (task.has("upload_dir") && !task.get("upload_dir").isJsonNull()) {
                                    targetDirId = task.get("upload_dir").getAsLong();
                                }

                                if (targetDirId == null || targetDirId == 0) {
                                    log.warn("离线任务没有目标目录信息: taskId={}", taskId);
                                    return null;
                                }

                                // 获取任务名称（用于匹配文件）
                                String taskName = task.get("name").getAsString();
                                log.debug("尝试在目录 {} 中查找所有相关文件: {}", targetDirId, taskName);

                                // 查找所有相关文件
                                return findAllFilesInDirectory(targetDirId, taskName, targetDirId);
                            }
                        }

                        log.warn("未找到离线任务: taskId={}", taskId);
                        return null;
                    });
        } catch (Exception e) {
            log.error("获取文件重命名信息失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private FileRenameInfo getFileRenameInfo(String taskId) {
        try {
            // 获取离线任务详情
            JsonObject body = new JsonObject();
            body.addProperty("current_page", 1);
            body.addProperty("page_size", 100);
            JsonArray statusArr = new JsonArray();
            statusArr.add(2); // 只查询已完成的任务
            body.add("status_arr", statusArr);

            return HttpReq.post(getSignedUrl(B_API + "/offline_download/task/list"))
                    .header("authorization", "Bearer " + accessToken)
                    .header("origin", BASE_URL)
                    .header("referer", BASE_URL + "/")
                    .header("platform", "web")
                    .header("app-version", "3")
                    .header("Content-Type", "application/json")
                    .body(GsonStatic.toJson(body))
                    .thenFunction(res -> {
                        HttpReq.assertStatus(res);
                        JsonObject response = GsonStatic.fromJson(res.body(), JsonObject.class);

                        if (response.get("code").getAsInt() != 0) {
                            log.error("获取离线任务列表失败");
                            return null;
                        }

                        JsonObject data = response.getAsJsonObject("data");
                        if (data == null || !data.has("list") || data.get("list").isJsonNull()) {
                            log.debug("123 网盘离线任务列表为空");
                            return null;
                        }

                        JsonArray taskList = data.getAsJsonArray("list");

                        // 查找对应的任务
                        for (JsonElement element : taskList) {
                            JsonObject task = element.getAsJsonObject();
                            long currentTaskId = task.get("task_id").getAsLong();

                            if (String.valueOf(currentTaskId).equals(taskId)) {
                                // 打印任务的完整信息，用于调试
                                log.debug("找到离线任务，完整信息: {}", task.toString());

                                // 获取目标目录 ID
                                Long targetDirId = null;
                                if (task.has("upload_idr") && !task.get("upload_idr").isJsonNull()) {
                                    targetDirId = task.get("upload_idr").getAsLong();
                                } else if (task.has("upload_dir") && !task.get("upload_dir").isJsonNull()) {
                                    targetDirId = task.get("upload_dir").getAsLong();
                                }

                                // 找到任务，获取文件 ID
                                if (task.has("file_id") && !task.get("file_id").isJsonNull()) {
                                    long fileId = task.get("file_id").getAsLong();
                                    if (fileId > 0) {
                                        String fileName = task.get("name").getAsString();
                                        log.debug("从离线任务中找到文件: {} (ID: {})", fileName, fileId);
                                        return new FileRenameInfo(fileId, fileName, targetDirId, true);
                                    }
                                }

                                // 尝试 upload_idr 字段（123 网盘 API 的拼写错误）
                                if (task.has("upload_idr") && !task.get("upload_idr").isJsonNull()) {
                                    long uploadDir = task.get("upload_idr").getAsLong();
                                    String taskName = task.get("name").getAsString();
                                    log.debug("尝试在目录 {} 中查找文件: {}", uploadDir, taskName);
                                    FileRenameInfo info = findFileRenameInfoInDirectory(uploadDir, taskName, uploadDir);
                                    if (info != null) {
                                        info.targetDirId = uploadDir;
                                        return info;
                                    }
                                }

                                // 如果任务中没有 file_id，尝试通过目录查找
                                if (task.has("upload_dir") && !task.get("upload_dir").isJsonNull()) {
                                    long uploadDir = task.get("upload_dir").getAsLong();
                                    String taskName = task.get("name").getAsString();
                                    log.debug("尝试在目录 {} 中查找文件: {}", uploadDir, taskName);
                                    FileRenameInfo info = findFileRenameInfoInDirectory(uploadDir, taskName, uploadDir);
                                    if (info != null) {
                                        info.targetDirId = uploadDir;
                                        return info;
                                    }
                                }

                                // 尝试其他可能的字段名
                                if (task.has("FileId") && !task.get("FileId").isJsonNull()) {
                                    long fileId = task.get("FileId").getAsLong();
                                    if (fileId > 0) {
                                        String fileName = task.get("name").getAsString();
                                        log.debug("从离线任务中找到文件 (FileId): {} (ID: {})", fileName, fileId);
                                        return new FileRenameInfo(fileId, fileName, targetDirId, true);
                                    }
                                }

                                // 尝试从 parent_file_id 和 name 查找
                                if (task.has("parent_file_id") && !task.get("parent_file_id").isJsonNull()) {
                                    long parentFileId = task.get("parent_file_id").getAsLong();
                                    String taskName = task.get("name").getAsString();
                                    log.debug("尝试在父目录 {} 中查找文件: {}", parentFileId, taskName);
                                    FileRenameInfo info = findFileRenameInfoInDirectory(parentFileId, taskName, targetDirId != null ? targetDirId : parentFileId);
                                    if (info != null) {
                                        if (info.targetDirId == null) {
                                            info.targetDirId = targetDirId;
                                        }
                                        return info;
                                    }
                                }

                                log.warn("离线任务中没有文件 ID 信息: taskId={}", taskId);
                                return null;
                            }
                        }

                        log.warn("未找到离线任务: taskId={}", taskId);
                        return null;
                    });
        } catch (Exception e) {
            log.error("获取文件重命名信息失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 在指定目录中查找文件重命名信息（递归查找）
     * @param parentId 父目录 ID
     * @param fileName 文件名（可能不包含扩展名）
     * @param targetDirId 目标目录 ID（用于判断文件是否在目标目录）
     * @return 文件重命名信息，如果未找到返回 null
     */
    private FileRenameInfo findFileRenameInfoInDirectory(long parentId, String fileName, Long targetDirId) {
        try {
            return HttpReq.get(getSignedUrl(B_API + "/file/list/new"))
                    .header("authorization", "Bearer " + accessToken)
                    .header("origin", BASE_URL)
                    .header("referer", BASE_URL + "/")
                    .header("platform", "web")
                    .header("app-version", "3")
                    .form("driveId", 0)
                    .form("limit", 100)
                    .form("parentFileId", parentId)
                    .form("trashed", false)
                    .form("orderBy", "file_id")
                    .form("orderDirection", "desc")
                    .thenFunction(res -> {
                        HttpReq.assertStatus(res);
                        JsonObject response = GsonStatic.fromJson(res.body(), JsonObject.class);

                        if (response.get("code").getAsInt() != 0) {
                            return null;
                        }

                        JsonObject data = response.getAsJsonObject("data");
                        if (data == null || !data.has("InfoList")) {
                            return null;
                        }

                        JsonArray infoList = data.getAsJsonArray("InfoList");

                        // 第一遍：优先查找视频文件（.mkv, .mp4, .avi 等）
                        List<String> videoExtensions = List.of(".mkv", ".mp4", ".avi", ".mov", ".wmv", ".flv", ".webm");
                        for (JsonElement element : infoList) {
                            JsonObject file = element.getAsJsonObject();
                            String currentFileName = file.get("FileName").getAsString();
                            int type = file.get("Type").getAsInt();

                            // Type: 0-文件 1-文件夹
                            if (type == 0) {
                                // 检查是否是视频文件
                                boolean isVideo = videoExtensions.stream()
                                        .anyMatch(ext -> currentFileName.toLowerCase().endsWith(ext));

                                if (isVideo) {
                                    String nameWithoutExt = currentFileName;
                                    int lastDot = currentFileName.lastIndexOf('.');
                                    if (lastDot > 0) {
                                        nameWithoutExt = currentFileName.substring(0, lastDot);
                                    }

                                    if (currentFileName.equals(fileName) || nameWithoutExt.equals(fileName) || currentFileName.contains(fileName)) {
                                        long fileId = file.get("FileId").getAsLong();
                                        // 判断文件是否在目标目录
                                        boolean isInTargetDir = (targetDirId != null && parentId == targetDirId);
                                        log.debug("在目录中找到视频文件: {} (ID: {}), 父目录ID: {}, 是否在目标目录: {}",
                                                currentFileName, fileId, parentId, isInTargetDir);
                                        return new FileRenameInfo(fileId, currentFileName, targetDirId, parentId, isInTargetDir);
                                    }
                                }
                            }
                        }

                        // 第二遍：如果没找到视频文件，查找其他匹配的文件
                        for (JsonElement element : infoList) {
                            JsonObject file = element.getAsJsonObject();
                            String currentFileName = file.get("FileName").getAsString();
                            int type = file.get("Type").getAsInt();

                            // Type: 0-文件 1-文件夹
                            // 匹配文件名（忽略扩展名）
                            if (type == 0) {
                                String nameWithoutExt = currentFileName;
                                int lastDot = currentFileName.lastIndexOf('.');
                                if (lastDot > 0) {
                                    nameWithoutExt = currentFileName.substring(0, lastDot);
                                }

                                if (currentFileName.equals(fileName) || nameWithoutExt.equals(fileName) || currentFileName.contains(fileName)) {
                                    long fileId = file.get("FileId").getAsLong();
                                    // 判断文件是否在目标目录
                                    boolean isInTargetDir = (targetDirId != null && parentId == targetDirId);
                                    log.debug("在目录中找到文件: {} (ID: {}), 父目录ID: {}, 是否在目标目录: {}",
                                            currentFileName, fileId, parentId, isInTargetDir);
                                    return new FileRenameInfo(fileId, currentFileName, targetDirId, parentId, isInTargetDir);
                                }
                            }
                        }

                        // 第三遍：递归查找子文件夹
                        for (JsonElement element : infoList) {
                            JsonObject file = element.getAsJsonObject();
                            int type = file.get("Type").getAsInt();

                            // 如果是文件夹，递归查找
                            if (type == 1) {
                                long subFolderId = file.get("FileId").getAsLong();
                                String folderName = file.get("FileName").getAsString();
                                log.debug("递归查找子文件夹: {} (ID: {})", folderName, subFolderId);

                                FileRenameInfo result = findFileRenameInfoInDirectory(subFolderId, fileName, targetDirId);
                                if (result != null) {
                                    return result;
                                }
                            }
                        }

                        log.debug("在目录中未找到文件: {}", fileName);
                        return null;
                    });
        } catch (Exception e) {
            log.warn("在目录中查找文件失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 在指定目录中查找所有相关文件（递归查找，包括视频和字幕）
     * @param parentId 父目录 ID
     * @param fileName 文件名（可能不包含扩展名）
     * @param targetDirId 目标目录 ID（用于判断文件是否在目标目录）
     * @return 所有匹配的文件列表
     */
    private List<FileRenameInfo> findAllFilesInDirectory(long parentId, String fileName, Long targetDirId) {
        try {
            return HttpReq.get(getSignedUrl(B_API + "/file/list/new"))
                    .header("authorization", "Bearer " + accessToken)
                    .header("origin", BASE_URL)
                    .header("referer", BASE_URL + "/")
                    .header("platform", "web")
                    .header("app-version", "3")
                    .form("driveId", 0)
                    .form("limit", 100)
                    .form("parentFileId", parentId)
                    .form("trashed", false)
                    .form("orderBy", "file_id")
                    .form("orderDirection", "desc")
                    .thenFunction(res -> {
                        HttpReq.assertStatus(res);
                        JsonObject response = GsonStatic.fromJson(res.body(), JsonObject.class);

                        if (response.get("code").getAsInt() != 0) {
                            return null;
                        }

                        JsonObject data = response.getAsJsonObject("data");
                        if (data == null || !data.has("InfoList")) {
                            return null;
                        }

                        JsonArray infoList = data.getAsJsonArray("InfoList");
                        List<FileRenameInfo> result = new ArrayList<>();

                        // 提取文件名（不含扩展名）用于匹配
                        String fileNameWithoutExt = fileName;
                        int lastDot = fileName.lastIndexOf('.');
                        if (lastDot > 0) {
                            fileNameWithoutExt = fileName.substring(0, lastDot);
                        }

                        // 查找所有匹配的文件（视频 + 字幕）
                        for (JsonElement element : infoList) {
                            JsonObject file = element.getAsJsonObject();
                            String currentFileName = file.get("FileName").getAsString();
                            int type = file.get("Type").getAsInt();

                            // Type: 0-文件 1-文件夹
                            if (type == 0) {
                                String currentNameWithoutExt = currentFileName;
                                int currentLastDot = currentFileName.lastIndexOf('.');
                                if (currentLastDot > 0) {
                                    currentNameWithoutExt = currentFileName.substring(0, currentLastDot);
                                }

                                // 匹配文件名（不含扩展名）
                                if (currentFileName.equals(fileName) ||
                                    currentNameWithoutExt.equals(fileNameWithoutExt) ||
                                    currentFileName.contains(fileNameWithoutExt)) {

                                    long fileId = file.get("FileId").getAsLong();
                                    boolean isInTargetDir = (targetDirId != null && parentId == targetDirId);

                                    log.debug("找到匹配文件: {} (ID: {}), 父目录ID: {}, 是否在目标目录: {}",
                                            currentFileName, fileId, parentId, isInTargetDir);

                                    result.add(new FileRenameInfo(fileId, currentFileName, targetDirId, parentId, isInTargetDir));
                                }
                            }
                        }

                        // 递归查找子文件夹
                        for (JsonElement element : infoList) {
                            JsonObject file = element.getAsJsonObject();
                            int type = file.get("Type").getAsInt();

                            if (type == 1) {
                                long subFolderId = file.get("FileId").getAsLong();
                                String folderName = file.get("FileName").getAsString();
                                log.debug("递归查找子文件夹: {} (ID: {})", folderName, subFolderId);

                                List<FileRenameInfo> subResult = findAllFilesInDirectory(subFolderId, fileName, targetDirId);
                                if (subResult != null && !subResult.isEmpty()) {
                                    result.addAll(subResult);
                                }
                            }
                        }

                        if (result.isEmpty()) {
                            log.debug("在目录中未找到匹配文件: {}", fileName);
                            return null;
                        }

                        return result;
                    });
        } catch (Exception e) {
            log.warn("在目录中查找所有文件失败: {}", e.getMessage());
            return null;
        }
    }
}
