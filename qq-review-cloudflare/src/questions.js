export const concentrationQuestions = [
  { q: "König 的梦想", options: ["狙击手", "厨师", "毛绒玩具生产商"], answer: 0 },
  { q: "Ghosts 的狙击手", options: ["Hesh", "Logan", "Keegan", "Merrick"], answer: 2 },
  { q: "谁喜欢雪茄", options: ["Ghost", "Price", "Merrick", "Soap"], answer: 1 },
  { q: "Krueger 的纹身图案", options: ["俄罗斯双头鹰", "俄罗斯猫头鹰", "俄罗斯白鹰", "俄罗斯黑鹰"], answer: 0 },
  { q: "暗影公司的老板是谁", options: ["Shepherd", "Makarov", "Zakhaev", "Graves"], answer: 3 },
  { q: "“去见你的祖宗吧”是谁说的", options: ["Horangi", "Oni", "Zimo", "Ghost"], answer: 2 },
  { q: "“半条狗”的笑话是谁说的", options: ["Ghost", "Soap", "Gaz", "Roach"], answer: 0 },
  { q: "谁的名字译名是 nobody", options: ["Ghost", "Nikto", "König", "Roach"], answer: 1 },
  { q: "谁不能吃巧克力", options: ["Ghost", "Riley", "Keegan", "Krueger"], answer: 1 },
  { q: "谁曾经沉迷赌博", options: ["Krueger", "Oni", "Horangi", "Y/N"], answer: 2 }
];

export const introQuestions = [
  { q: "小手机安装包支持哪些设备？", options: ["iPhone 和 Android", "仅限 Android", "Android 及部分鸿蒙用户", "所有手机均可"], answer: 2 },
  { q: "第一次进入小手机后界面全黑是什么原因？", options: ["软件崩溃", "UI 和背景图都是黑色，属于正常现象", "需要更新版本", "API 未配置"], answer: 1 },
  { q: "激活小手机时出现报错，最可能的原因是什么？", options: ["激活码错误", "没有开启科学上网", "手机内存不足", "安装包版本太旧"], answer: 1 },
  { q: "模型设置中拉取模型失败应该怎么处理？", options: ["卸载重装", "联系作者", "检查 API 端点，尝试去掉末尾 /v1 再拉取", "换手机"], answer: 2 },
  { q: "AI 生图时应选择什么类型的模型？", options: ["任意模型", "名称带 chat", "名称带 image", "价格最贵"], answer: 2 },
  { q: "等待生图完成时，哪个操作会导致失败？", options: ["保持软件在前台", "切换到其他 App", "降低亮度", "保持屏幕亮起"], answer: 1 },
  { q: "角色一直不回消息应该怎么处理？", options: ["删除角色", "换模型", "点击对话框右侧的小电视图标", "重启手机"], answer: 2 },
  { q: "TTS 配置完成后没有声音，最可能的原因是？", options: ["手机音量低", "音色字符或 TTS 密钥错误", "软件太旧", "模型不支持语音"], answer: 1 },
  { q: "关于角色耐心值，正确的是？", options: ["越高回复越快", "设置多久未回复后角色主动发消息", "影响生图质量", "越低 API 消耗越少"], answer: 1 },
  { q: "哈基米平台新建令牌时，怎样看到添加按钮？", options: ["直接点新建令牌", "令牌管理中先点显示操作项，再点添加令牌", "首页右上角创建", "控制台生成密钥"], answer: 1 },
  { q: "填完 API 端点和密钥后，下一步是什么？", options: ["直接退出", "重启软件", "点击拉取模型并从列表选择模型", "先填 TTS"], answer: 2 },
  {
    q: "遇到不会的问题怎么办？",
    type: "text",
    answerText: "看教程说明或者QQ群管家，都无法解答再发问"
  }
];
