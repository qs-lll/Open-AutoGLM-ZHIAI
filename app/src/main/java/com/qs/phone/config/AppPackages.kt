package com.qs.phone.config

/**
 * 应用名称到包名的映射
 */
object AppPackages {
    val packages: Map<String, String> = mapOf(
        // 社交通讯
        "微信" to "com.tencent.mm",
        "WeChat" to "com.tencent.mm",
        "QQ" to "com.tencent.mobileqq",
        "微博" to "com.sina.weibo",

        // 电商购物
        "淘宝" to "com.taobao.taobao",
        "京东" to "com.jingdong.app.mall",
        "京东秒送" to "com.jingdong.app.mall",
        "京东到家" to "com.jingdong.app.mall",
        "JD" to "com.jingdong.app.mall",
        "拼多多" to "com.xunmeng.pinduoduo",

        // 生活社交
        "小红书" to "com.xingin.xhs",
        "知乎" to "com.zhihu.android",
        "豆瓣" to "com.douban.frodo",

        // 地图导航
        "高德地图" to "com.autonavi.minimap",
        "百度地图" to "com.baidu.BaiduMap",

        // 外卖服务
        "美团" to "com.sankuai.meituan",
        "饿了么" to "me.ele",
        "大众点评" to "com.dianping.v1",

        // 出行旅游
        "携程" to "ctrip.android.view",
        "12306" to "com.MobileTicket",
        "滴滴出行" to "com.sdu.didi.psnger",

        // 视频娱乐
        "bilibili" to "tv.danmaku.bili",
        "抖音" to "com.ss.android.ugc.aweme",
        "快手" to "com.smile.gifmaker",
        "腾讯视频" to "com.tencent.qqlive",
        "爱奇艺" to "com.qiyi.video",

        // 音乐
        "网易云音乐" to "com.netease.cloudmusic",
        "QQ音乐" to "com.tencent.qqmusic",

        // 系统应用
        "Settings" to "com.android.settings",
        "设置" to "com.android.settings",
        "Chrome" to "com.android.chrome",
        "浏览器" to "com.android.browser",
        "相机" to "com.android.camera",
        "相册" to "com.android.gallery3d",
        "文件管理" to "com.android.fileexplorer",

        // 国际应用
        "Telegram" to "org.telegram.messenger",
        "WhatsApp" to "com.whatsapp",
        "Twitter" to "com.twitter.android",
        "X" to "com.twitter.android",
        "Gmail" to "com.google.android.gm",
        "Google Maps" to "com.google.android.apps.maps",
        "YouTube" to "com.google.android.youtube",
    )

    fun getPackageName(appName: String): String? = packages[appName]

    fun getAppName(packageName: String): String? {
        return packages.entries.find { it.value == packageName }?.key
    }
}
