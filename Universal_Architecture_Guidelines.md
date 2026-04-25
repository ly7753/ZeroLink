# 现代软件工程通用架构规范
**(Universal Architecture Guidelines)**

## 0. 概述与核心哲学 (Overview & Core Philosophy)
本规范确立了移动端与前端系统架构的工程基线，旨在构建高可用、可测试及可维护的软件系统。

**双轨驱动哲学 (Dual-Track Philosophy)**：
* **向外的“表” (UX & API UX - 体验驱动)**：以 Apple 的《Swift API Design Guidelines》与《Human Interface Guidelines》为精神内核。追求极致的终端交互感知与代码修辞美学。不仅服务于最终用户的流畅体验，更致力于降低 API 调用方（开发者）的心智负担。
* **向内的“里” (DX & Structural Integrity - 维护驱动)**：吸收 Google 等业界的《应用架构指南》。通过单向数据流 (UDF)、严格的物理模块隔离与依赖倒置，对抗软件长生命周期中的熵增，保障并发安全与数据鲁棒性。

## 1. 命名与代码规范 (Naming & Coding Conventions)
* **使用处清晰 (Clarity at the Point of Use)**：
  方法与属性的命名应确保在调用上下文中语义清晰且无歧义，不应为追求简短而牺牲可读性。
* **副作用的文法区分 (Mutating vs. Non-mutating)**：
  * 修改实例自身状态的操作（有副作用），应使用动词原形命名（如 `sort()`）。
  * 返回新值而不修改原实例的操作（无副作用），应使用 `-ed` 或 `-ing` 后缀命名（如 `sorted()`）。
* **布尔值语义 (Boolean Assertions)**：
  布尔类型的变量与方法命名应构成有效的断言语句（如 `isEditable`, `hasPermissions`）。
* **接口命名 (Protocol/Interface Naming)**：
  接口应描述行为或能力，实现类应描述具体的执行策略。严禁使用 `Impl` 等无业务语义的后缀。

## 2. 架构核心原则 (Core Architecture Principles)
* **单一数据源 (Single Source of Truth, SSOT)**：
  系统中的任何状态应仅存在唯一的权威数据源。表现层视图应严格作为状态的函数投影。
* **单向数据流 (Unidirectional Data Flow, UDF)**：
  状态的变更必须通过预定义的意图（Intent/Action）触发，经由状态机处理后，输出不可变的状态快照。禁止视图层直接反向修改状态。
* **依赖倒置与物理隔离 (Dependency Inversion & Physical Isolation)**：
  高层业务策略绝对不可依赖底层具体实现。必须通过多模块（Multi-module）构建有向无环图（DAG），在编译期强制物理阻断反向依赖。

## 3. 领域层设计 (Domain Layer)
* **环境无关性 (Context-Agnostic)**：
  核心业务逻辑与领域模型必须纯粹，严禁依赖任何特定的 UI 框架（如 Compose/UIKit）、操作系统 API（如 Context/Uri）或第三方 SDK。
* **优先使用不可变值类型 (Immutable Value-Type Preference)**：
  领域实体应优先采用不可变的数据载体（如 Kotlin `data class`）进行建模，消除引用共享带来的副作用与竞态条件风险。

## 4. 表现层设计 (Presentation Layer)
* **性能基线 (Performance Baseline)**：
  用户交互应在 100ms 内提供状态反馈。主线程的工作负载应被严格控制，避免阻塞超过当前屏幕刷新率的单帧时间。
* **状态防抖与局部订阅 (State Memoization)**：
  UI 组件应仅订阅其需要的局部状态切片。必须引入状态对比机制（如 Flow `distinctUntilChanged`），避免微小状态变更引发不必要的全局视图重建。
* **极端的防丢失与状态恢复 (State Preservation & Restoration)**：
  应用必须具备极强的上下文留存能力。在进程生命周期面临终结（如 `ON_STOP` 阶段）时，必须通过底层机制（如协程 `NonCancellable` 配合 DB 落盘）确保草稿和内存状态安全着陆，实现用户视角的“零感知”恢复。

## 5. 计算密集型任务与端侧处理 (Compute-Intensive & Edge Processing)
* **旁路处理引擎 (Bypass Processing Engine)**：
  对于端侧多媒体处理（如高频视频抽帧、音频波形提取、大文件哈希计算），必须设立完全独立、限制并行度的执行队列与引擎，严禁此类高频计算阻塞表现层状态机或耗尽默认线程池。
* **内存优化约束 (Memory Optimization)**：
  在底层处理引擎与应用层之间传递大容量媒体缓冲帧（Buffer）时，应采用流式处理或指针借用技术，避免不必要的内存深拷贝损耗。

## 6. 数据层与基础设施 (Data Layer & Infrastructure)
* **数据迁移策略 (Data Migration Strategy)**：
  持久化存储（如 SQL/Preferences）的 Schema 变更必须提供显式的迁移路径。禁止在生产环境使用破坏性降级作为默认的失败处理策略。
* **无感鉴权与权限收敛 (Permission Convergence)**：
  访问敏感用户资产时，优先采用系统提供的无权调用组件（如 Photo Picker）。对于必需的硬性权限，必须在隐私清单中合规声明并提供优雅的降级解释。
* **预留可观测性抽象 (Observability Abstraction)**：
  基础设施层应预留标准化的低损耗遥测（Telemetry）接口抽象，以便在未来无侵入式地接入日志追踪与性能指标大盘。

## 7. 并发与异步调度 (Concurrency & Dispatching)
* **主线程安全 (Main-Safety)**：
  所有数据层与领域层提供的异步接口，必须在内部自行挂载至适当的后台调度器（Dispatchers.IO/Default），调用方（表现层）无需承担线程切换的责任。
* **结构化并发 (Structured Concurrency)**：
  所有的异步任务必须受控于明确的生命周期作用域。当组件（如 ScreenModel/ViewModel）销毁时，必须确定性地级联取消其作用域内的所有挂起任务，杜绝内存泄漏。

## 8. 错误处理 (Error Handling)
* **强类型错误边界 (Strongly-Typed Error Boundaries)**：
  业务边界处应使用代数数据类型（如 `AppResult<V, E : AppError>`）封装返回值。利用编译器的穷尽性检查（Exhaustiveness Checking），强制调用方显式处理所有失败路径。
* **异常防腐与翻译 (Error Translation & ACL)**：
  底层原生异常（如 `IOException`, `SQLiteException`）绝对不可直接抛出至领域层或表现层。基础设施层必须将其捕获，并翻译为具有清晰业务语义的密封错误类（Sealed Interface）。

## 9. 访问控制与持续集成 (Access Control & CI)
* **严格的可见性降级 (Strict Visibility Protocol)**：
  模块内部类型及成员的访问级别默认应降级为 `internal` 或 `private`。仅明确对外的抽象接口协议与数据传输对象可暴露为 `public`。
* **静态架构检查 (Static Architecture Analysis)**：
  CI 流水线必须配置架构校验与静态分析工具（如 Detekt）。任何破坏分层原则的代码，均应在合并请求阶段被自动化门禁拦截。

## 10. 架构演进与技术债务 (Architecture Evolution & Tech Debt)
* **架构决策记录 (ADR)**：
  涉及引入新技术栈、多模块拆分或偏离核心架构规范的权衡性技术决策，必须通过标准 ADR 文档进行入库评审记录。
* **显式债务标记 (Explicit Debt Marking)**：
  因紧急需求引入的妥协性代码，必须使用 `@TechDebt` 注解或标准化注释进行标记，并强制附带明确的过期时间（TTL）或重构工单号，确保持续演进过程中的债务透明化。
