package org.fcitx.fcitx5.android.utils

/** Simplified Chinese sentence parts chosen to form readable subject–verb–object sentences. */
internal object MessagePhraseCipherSentenceBook {
    val subjects: List<String> = listOf(
        "我", "你", "他", "她", "我们", "你们", "他们", "大家",
        "人们", "孩子", "少年", "青年", "老人", "朋友", "同学", "学生",
        "老师", "医生", "记者", "作者", "读者", "听众", "观众", "乘客",
        "游客", "顾客", "员工", "同事", "家人", "邻居", "村民", "居民",
        "志愿者", "研究者", "设计师", "工程师", "画家", "歌手", "演员", "作家",
        "厨师", "司机", "农民", "运动员", "队员", "伙伴", "选手", "主人",
        "来客", "商人", "老板", "同伴", "家长", "参观者", "服务员", "管理者",
        "决策者", "观察者", "学者", "专家", "青年教师", "演奏者", "参与者", "组织者",
    ).also { check(it.size == 64 && it.toSet().size == it.size) }

    val predicateObjects: List<String> = listOf(
        "喜欢" to listOf("音乐", "书籍", "旅行", "美好生活"),
        "欣赏" to listOf("风景", "艺术", "作品", "大自然"),
        "阅读" to listOf("故事", "新闻", "文章", "精彩故事"),
        "寻找" to listOf("答案", "机会", "新方向", "伙伴"),
        "解决" to listOf("问题", "困难", "矛盾", "麻烦"),
        "分享" to listOf("快乐", "经验", "故事", "美食"),
        "学习" to listOf("知识", "技能", "方法", "历史"),
        "珍惜" to listOf("时间", "友情", "机会", "生活"),
        "追求" to listOf("理想", "梦想", "自由", "进步"),
        "计划" to listOf("未来", "行程", "工作", "生活"),
        "创造" to listOf("机会", "价值", "美好", "奇迹"),
        "记录" to listOf("生活", "故事", "变化", "心情"),
        "了解" to listOf("历史", "文化", "情况", "自己"),
        "关注" to listOf("新闻", "健康", "发展", "未来"),
        "选择" to listOf("方向", "道路", "方案", "生活"),
        "完成" to listOf("任务", "目标", "工作", "计划"),
        "参加" to listOf("活动", "比赛", "会议", "旅行"),
        "接受" to listOf("挑战", "建议", "帮助", "现实"),
        "发现" to listOf("问题", "变化", "机会", "美好"),
        "保护" to listOf("环境", "家园", "动物", "健康"),
        "建设" to listOf("家园", "城市", "未来", "团队"),
        "照顾" to listOf("家人", "朋友", "孩子", "生活"),
        "帮助" to listOf("朋友", "家人", "同伴", "别人"),
        "表达" to listOf("观点", "想法", "感情", "心意"),
        "传递" to listOf("消息", "信息", "温暖", "希望"),
        "讨论" to listOf("问题", "办法", "计划", "未来"),
        "探索" to listOf("世界", "自然", "科学", "未知"),
        "观察" to listOf("自然", "生活", "变化", "现象"),
        "研究" to listOf("科学", "历史", "文化", "问题"),
        "体验" to listOf("生活", "旅行", "自然", "过程"),
        "制定" to listOf("计划", "目标", "规则", "方案"),
        "改变" to listOf("生活", "现状", "环境", "未来"),
        "推动" to listOf("发展", "进步", "合作", "变化"),
        "讲述" to listOf("故事", "经历", "历史", "心情"),
        "创作" to listOf("作品", "故事", "音乐", "诗歌"),
        "设计" to listOf("方案", "作品", "图案", "未来"),
        "播放" to listOf("音乐", "电影", "视频", "节目"),
        "传播" to listOf("文化", "知识", "信息", "希望"),
        "维护" to listOf("秩序", "关系", "权益", "和平"),
        "守护" to listOf("家园", "安全", "自然", "和平"),
        "实现" to listOf("梦想", "理想", "目标", "愿望"),
        "展开" to listOf("讨论", "合作", "行动", "调查"),
        "建立" to listOf("联系", "信任", "关系", "团队"),
        "收集" to listOf("资料", "信息", "意见", "故事"),
        "整理" to listOf("信息", "资料", "房间", "思路"),
        "检查" to listOf("结果", "问题", "设备", "作业"),
        "改进" to listOf("方法", "工作", "生活", "服务"),
        "提升" to listOf("能力", "效率", "水平", "质量"),
        "带来" to listOf("希望", "快乐", "变化", "机会"),
        "感受" to listOf("春天", "快乐", "温暖", "生活"),
        "理解" to listOf("生活", "文化", "感受", "想法"),
        "认识" to listOf("朋友", "自己", "世界", "问题"),
        "记住" to listOf("名字", "故事", "教训", "约定"),
        "享受" to listOf("生活", "阳光", "安宁", "美食"),
        "迎接" to listOf("未来", "挑战", "变化", "春天"),
        "支持" to listOf("朋友", "家人", "工作", "计划"),
        "寻求" to listOf("帮助", "建议", "机会", "合作"),
        "承担" to listOf("责任", "任务", "风险", "压力"),
        "改善" to listOf("生活", "环境", "关系", "服务"),
        "提供" to listOf("帮助", "信息", "机会", "支持"),
        "组织" to listOf("活动", "会议", "团队", "比赛"),
        "发表" to listOf("观点", "意见", "文章", "作品"),
        "遵守" to listOf("规则", "法律", "约定", "秩序"),
        "开发" to listOf("产品", "技术", "软件", "资源"),
    ).flatMap { (verb, objects) -> objects.map { verb + it } }
        .also { check(it.size == 256 && it.toSet().size == it.size) }

    val sentences: List<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        subjects.flatMap { subject -> predicateObjects.map { subject + it } }
            .also { check(it.size == SENTENCE_RADIX && it.toSet().size == it.size) }
    }

    val sentenceIndices: Map<String, Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        HashMap<String, Int>(SENTENCE_RADIX * 4 / 3 + 1).apply {
            sentences.forEachIndexed { index, sentence -> check(put(sentence, index) == null) }
        }
    }

    fun sentenceFor(index: Int): String {
        require(index in 0 until SENTENCE_RADIX)
        return subjects[index / predicateObjects.size] + predicateObjects[index % predicateObjects.size]
    }

    private const val SENTENCE_RADIX = 64 * 256
}
