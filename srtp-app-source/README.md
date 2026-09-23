# SRTP Desktop - TSP算法优化平台

## 项目简介

SRTP Desktop 是一个基于 **Electron + Python** 的桌面应用，用于**旅行商问题 (TSP)** 的多算法求解与对比分析。内置 8 种经典算法，支持用户自定义城市数据、参数调优、批量对比运行，以及客户历史记录管理。

### 8 种算法

| 算法 | 代号 | 说明 |
|------|------|------|
| **粒子群优化** | PSO | 适合中大规模问题，收敛速度快 |
| **模拟退火** | SA | 搜索稳定，擅长跳出局部最优 |
| **遗传算法** | GA | 全局探索能力强，参数可调范围大 |
| **蚁群算法** | ACA | 路径构建直观，结果可解释性较高 |
| **分支定界** | BnB | 精确最优解，仅适用于 ≤15 城 |
| **插入启发式** | DP | 最便宜插入策略，速度极快但非精确解 |
| **自组织映射** | SOM | 神经网络启发式，适合中大规模 |
| **禁忌搜索** | TS | 邻域搜索 + 禁忌表避免循环，收敛稳定 |

## 项目结构

```
srtp-app-source/
├── 算法启动菜单.py          # 后端主程序 (HTTP API 服务器)
├── 四算法整合优化.py        # 算法核心 + Tkinter GUI
├── main.js                  # Electron 主进程
├── preload.js               # Electron 预加载脚本
├── package.json             # Electron 配置
├── requirements.txt         # Python 依赖
├── build.bat                # 构建脚本
└── launcher_web/            # Web 前端资源
    ├── index.html           # 主控制台
    ├── login.html           # 登录页面
    ├── login.js             # 登录逻辑
    ├── app.js               # 主控制台逻辑
    └── styles.css           # 样式表
```

## 运行方式

### 方式 1: 直接运行 Python 后端 (开发模式)

```bash
# 安装 Python 依赖
pip install -r requirements.txt

# 启动后端服务器
python 算法启动菜单.py

# 浏览器访问 http://127.0.0.1:8090/login
```

### 方式 2: 运行桌面 GUI

```bash
pip install -r requirements.txt
python 四算法整合优化.py
```

### 方式 3: 完整的 Electron 桌面应用

详见 `build.bat` 构建脚本。

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/algorithms` | 获取算法列表和默认参数 |
| POST | `/api/auth/register` | 用户注册 |
| POST | `/api/auth/login` | 用户登录 |
| POST | `/api/auth/logout` | 用户登出 |
| GET | `/api/auth/me` | 获取当前用户 |
| POST | `/api/run-selected` | 运行选中的算法 |
| GET | `/api/history` | 查询历史记录 |
| DELETE | `/api/history/:id` | 删除单条记录 |
| POST | `/api/history/delete-batch` | 批量删除记录 |

## 技术栈

- **前端**: HTML5 + CSS3 + Vanilla JavaScript
- **后端**: Python 3.13 (HTTP Server + 算法引擎)
- **桌面框架**: Electron
- **数据存储**: SQLite
- **导出**: openpyxl (Excel), python-docx (Word)
- **数值计算**: NumPy

## 注意事项

本项目为从已编译的安装包中逆向还原所得。Python 3.13 字节码的反编译工具尚不完全成熟，源代码已尽量从反汇编中准确重建，但可能存在少量细节差异。前端文件 (`launcher_web/`) 为原始未编译资源，直接提取还原。

## 许可

SRTP Desktop 为教育/研究用途。请遵守原始软件的相关许可条款。
