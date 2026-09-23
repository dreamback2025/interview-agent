# Java 后端面试笔记（RAG 评测语料）

> 每条以 `## <tag> | <知识点名>` 开头，import 脚本按其切分为独立文档，
> tag 用作检索命中判定的唯一标识（同一条 tag 在检索结果的 tags 中出现即为命中）。
> 措辞刻意贴近手写笔记：带具体参数、数字、流程，不写成百科条目。

## jvm-memory | JVM 运行时内存区域与参数

JVM 运行时数据区分五块：程序计数器、虚拟机栈、本地方法栈、堆、方法区（JDK8 后在元空间 Metaspace）。

程序计数器是唯一不会 OOM 的区域，记录当前线程执行的字节码行号。虚拟机栈是线程私有的，一个方法对应一个栈帧（局部变量表、操作数栈、动态链接、返回地址），栈深度超限抛 StackOverflowError，扩展失败抛 OOM。堆是线程共享的最大区域，存放对象实例，分新生代（Eden + 两个 Survivor，默认 8:1:1）和老年代，默认比例 1:2。

方法区 JDK7 是永久代（受 -XX:MaxPermSize 限制，易 OOM），JDK8 改为元空间，使用本地内存，由 -XX:MaxMetaspaceSize 控制，默认不限（但受物理内存约束）。

常用参数：-Xms / -Xmx 设置堆初始与最大值（生产建议设成相等，避免动态扩容引起抖动）、-Xmn 新生代大小、-XX:SurvivorRatio 设置 Eden 与单个 Survivor 的比例、-XX:MetaspaceSize 元空间初始阈值、-Xss 单线程栈大小。

## jvm-gc | 垃圾收集器与选型

判定对象可回收用可达性分析（GC Roots 包括栈中引用、静态变量、常量、JNI 引用），不用引用计数（无法解决循环引用）。

分代收集理论：弱分代假说（绝大多数对象朝生夕死）、强分代假说（熬过越多次回收的对象越难死）、跨代引用假说（用记忆集 Remembered Set 记录跨代引用，避免全堆扫描）。

各收集器特点：
- Serial / Serial Old：单线程，简单，适合客户端小程序。
- ParNew：Serial 的多线程版，配合 CMS。
- Parallel Scavenge / Parallel Old：吞吐量优先（吞吐量 = 用户代码时间 / 总时间），适合后台批处理。
- CMS：以最短停顿为目标，并发标记清除，有内存碎片和 Concurrent Mode Failure 问题，JDK9 弃用、JDK14 移除。
- G1：JDK9 起默认，把堆划成等大 Region，用停顿预测模型在目标停顿内回收收益最高的 Region。
- ZGC：着色指针 + 读屏障，超大堆（TB 级）下停顿也能保持 10ms 以内，适合低延迟大内存服务。
- Shenandoah：与 ZGC 定位类似。

选型建议：中小堆 + 高吞吐选 Parallel；通用服务选 G1；大堆低延迟（如行情、风控）选 ZGC。

## jvm-gc-tuning | GC 调优与停顿排查

排查思路顺序很重要：先确认是不是 GC 问题，再定位是哪一种 GC 问题，最后才动参数。

第一步，看 GC 日志。JDK9+ 用 -Xlog:gc*:file=gc.log:time,uptime,level,tags 打开，JDK8 用 -XX:+PrintGCDetails -XX:+PrintGCDateStamps -Xloggc:gc.log。重点看：Young GC 频率、单次耗时、Full GC 次数、老年代回收后剩余大小。

第二步，判断问题类型：
- Young GC 频繁（几秒一次）：新生代太小或对象晋升阈值不合理，调 -Xmn 或 -XX:SurvivorRatio，也可能是接口产生了大量临时对象。
- Full GC 频繁但老年代回收后能降下来：大概率是晋升过快，检查 -XX:MaxTenuringThreshold 和 Survivor 是否过小导致动态晋升。
- Full GC 后老年代几乎不降：内存泄漏或大对象常驻，用 jmap -histo:live 看对象直方图，或 MAT 分析 dump。
- 单次停顿过长（超过目标）：G1 看 -XX:MaxGCPauseMillis 是否设得过小、Region 是否过小导致 Humongous 分配；ZGC 一般不用担心。

第三步，工具：jstat -gcutil <pid> 1000 观察各代占用与 GC 次数，jmap -dump:live 导出堆快照，arthas 的 dashboard / vmtool 在线看。

常见误区：把 -Xmx 设置得远大于实际需要，导致 Full GC 一次要回收很久；或者 -Xms 和 -Xmx 不等，堆反复伸缩。

## jvm-classload | 类加载机制与双亲委派

类的生命周期：加载 → 验证 → 准备 → 解析 → 初始化 → 使用 → 卸载。前五步合称类加载。

加载阶段：通过类的全限定名获取二进制字节流，生成 Class 对象。

准备阶段：给静态变量分配内存并赋零值（`static int a = 10` 此时 a=0，到初始化阶段才赋 10）；但 `static final` 常量在准备阶段就赋真实值。

解析阶段：把常量池的符号引用替换为直接引用。

初始化阶段：执行 `<clinit>` 方法，即静态变量赋值和静态代码块，父类先于子类，接口不要求。

类加载器层次：BootstrapClassLoader（C++ 实现，加载 JAVA_HOME/lib）→ ExtensionClassLoader（JDK9 后叫 PlatformClassLoader）→ ApplicationClassLoader（加载 classpath）。还有自定义 ClassLoader。

双亲委派：加载类时先委托父加载器，父加载器加载不了才自己加载。作用：保证核心类库唯一（避免用户自定义 java.lang.String 被加载）、防止核心类被篡改。

打破双亲委派的场景：Tomcat 的 WebAppClassLoader（每个 webapp 独立加载自己的类，实现应用隔离）、SPI 机制（JDBC 的 DriverManager 由 Bootstrap 加载，但驱动实现由线程上下文类加载器加载）、OSGi。

## jvm-oom | OOM 与内存泄漏排查

常见 OOM 类型与成因：
- java.lang.OutOfMemoryError: Java heap space —— 堆内存不足，通常是对象泄漏或大对象/大集合。
- GC overhead limit exceeded —— GC 占用了 98% 以上时间却回收不到 2% 内存，实质也是堆不足。
- Metaspace —— 动态生成类过多（CGLIB 代理、Groovy 脚本、频繁热部署）。
- Direct buffer memory —— NIO 直接内存不足，-XX:MaxDirectMemorySize 控制。
- unable to create new native thread —— 线程数超过系统限制，常见于线程池未复用、每次请求 new Thread。
- GC 时间过长 / 堆外内存不足。

排查步骤：
1. 启动加 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/data/dump 保证现场不丢。
2. jmap -histo:live <pid> | head -20 看什么对象最多。
3. jmap -dump:live,format=b,file=heap.hprof <pid> 导出，用 MAT 打开，看 Dominator Tree 和 Leak Suspects。
4. 常见泄漏源：静态集合不断 add、ThreadLocal 未 remove、监听器注册未反注册、连接/流未关闭、缓存无淘汰策略。

## concurrency-lock | synchronized 原理与锁升级

synchronized 修饰代码块用 monitorenter / monitorexit 字节码指令，修饰方法用 ACC_SYNCHRONIZED 标志。

锁存在对象头的 Mark Word 里，JDK6 引入锁升级（不可逆）：无锁 → 偏向锁 → 轻量级锁 → 重量级锁。

- 偏向锁：第一个进入的线程把线程 ID 记到 Mark Word，之后同一线程再进入无需 CAS。JDK15 起默认关闭（-XX:+UseBiasedLocking 已废弃），因为撤销偏向锁要 STW，高并发下得不偿失。
- 轻量级锁：CAS 把 Mark Word 指向栈帧中的 Lock Record，失败则自旋，自旋失败升级。
- 重量级锁：依赖操作系统 mutex，未抢到锁的线程直接阻塞挂起，涉及用户态到内核态切换，开销大。

锁优化：锁消除（逃逸分析判定不可能被共享则去掉）、锁粗化（相邻的加锁操作合并）、自适应自旋（根据上次自旋成功率动态调整）。

与 ReentrantLock 对比：synchronized 是关键字，JVM 层面实现，自动释放，不可中断、不可超时、非公平；ReentrantLock 是 API 层面，需手动 unlock（放 finally），支持公平锁、可中断 lockInterruptibly、超时 tryLock、多条件 Condition。

## concurrency-aqs | AQS 原理

AQS（AbstractQueuedSynchronizer）用一个 volatile int state 表示同步状态，加一个 CLH 变体的双向 FIFO 等待队列存被阻塞的线程。

核心方法：tryAcquire / tryRelease、tryAcquireShared / tryReleaseShared、isHeldExclusively，子类只需实现这几个方法。

独占模式（ReentrantLock）：state=0 表示未占用，CAS 改成 1 成功即获得锁；重入时 state 累加。获取失败则包装成 Node 入队，然后 LockSupport.park 挂起。

共享模式（Semaphore、CountDownLatch、ReadWriteLock 的读锁）：state 表示可用许可数或计数。

ReentrantLock 的公平与非公平：公平锁在 tryAcquire 里先检查队列有没有前驱（hasQueuedPredecessors），有就直接入队，保证 FIFO；非公平锁直接 CAS 抢，抢不到再入队，允许插队，吞吐量更高（减少线程切换）。

Condition：对应一个条件等待队列，await 时释放锁并 park，signal 时把节点从条件队列转移到同步队列。

## concurrency-pool | 线程池参数与拒绝策略

ThreadPoolExecutor 七个参数：corePoolSize、maximumPoolSize、keepAliveTime、unit、workQueue、threadFactory、handler。

执行流程：核心线程数未满则新建核心线程 → 队列未满则入队 → 线程数未达最大则新建非核心线程 → 触发拒绝策略。注意：**先入队再加线程**这个顺序意味着用无界队列时 maximumPoolSize 永远不会生效。

队列选择：
- LinkedBlockingQueue：默认无界（其实 Integer.MAX_VALUE），风险是任务堆积导致 OOM，适合作为固定线程池。
- ArrayBlockingQueue：有界，推荐。
- SynchronousQueue：不存储，直接交接，配合大 maximumPoolSize，是 newCachedThreadPool 的做法。
- PriorityBlockingQueue：优先级任务。

参数估算：
- CPU 密集型：核心数 = CPU 核数 + 1，减少线程切换。
- IO 密集型：核心数 = CPU 核数 × 2，或按 核数 × (1 + 平均等待时间 / 平均计算时间) 计算。

四种拒绝策略：
- AbortPolicy：抛 RejectedExecutionException，默认，除非有明确补偿逻辑。
- CallerRunsPolicy：由提交任务的线程自己执行，天然形成背压，不丢任务。
- DiscardPolicy：静默丢弃，生产慎用。
- DiscardOldestPolicy：丢队列里最老的再提交，适合只关心最新任务的场景。

线程池数量失控的常见原因：把线程池声明成局部变量（每次请求都新建）、用 Executors.newCachedThreadPool 且没有上限。

## concurrency-cas | volatile、CAS 与 ABA

volatile 两条语义：可见性（写操作立即刷回主内存，读操作从主内存读，底层是 lock 前缀指令 + 缓存一致性协议 MESI）、禁止指令重排（插入内存屏障，LoadLoad / StoreStore / LoadStore / StoreLoad）。不保证原子性，`i++` 用 volatile 修饰仍然线程不安全。

内存屏障与 happens-before：程序顺序规则、监视器锁规则、volatile 变量规则、传递性。

CAS：compareAndSwap，比较内存值与期望值，相等才更新，由 CPU 的 cmpxchg 指令保证原子。三大问题：
1. 循环时间长开销大 —— 高竞争下自旋消耗 CPU，可用 LongAdder（分段累加 Cell）替代 AtomicLong。
2. 只能保证一个变量的原子性 —— 多个变量可封装成对象用 AtomicReference，或加锁。
3. ABA 问题 —— 值从 A 改成 B 又改回 A，CAS 察觉不到，用 AtomicStampedReference 加版本号解决。

适用场景：计数器、状态标志、单点初始化的无锁实现。

## concurrency-threadlocal | ThreadLocal 与内存泄漏

每个 Thread 内部有一个 ThreadLocalMap，key 是 ThreadLocal 的弱引用（WeakReference），value 是强引用。

泄漏原因：key 被 GC 回收后变成 null，但 value 还被 Entry 强引用着，且 Thread 长期存活（线程池里的线程几乎永不销毁）→ value 无法回收。

设计上已经把 key 设计成弱引用（尽量避免泄漏），但 value 不会自动清理，所以**必须显式调用 remove()**，标准写法是 try-finally。

ThreadLocalMap 的哈希冲突用开放地址法（线性探测），不像 HashMap 用链表；size 到达阈值时先清理 key 为 null 的 Entry（expungeStaleEntry），清理后仍超阈值才扩容。

典型用途：链路追踪的 traceId 透传、Spring 的 RequestContextHolder、事务上下文、SimpleDateFormat 复用（但现在更推荐 DateTimeFormatter，它本来就是线程安全的）。

跨线程传递：InheritableThreadLocal 可以传给子线程（复制父线程的 map），但线程池场景下子线程是复用的，会读到脏数据，应该用阿里开源的 TransmittableThreadLocal。

## concurrency-collection | ConcurrentHashMap 与并发容器

JDK7 用分段锁 Segment（继承 ReentrantLock），默认 16 段，锁粒度是段。JDK8 改为 CAS + synchronized 锁单个桶头节点，粒度更细，并发度更高。

JDK8 的主要变化：
- 初始化用 sizeCtl 的 CAS 控制，懒初始化。
- put 时桶为空则 CAS 放入，否则 synchronized 锁住头节点再遍历链表/红黑树。
- 链表长度超过 8 且数组长度 ≥ 64 转红黑树，退化阈值是 6。
- 扩容支持多线程协同（ForwardingNode 标记已迁移的桶，其他线程可以来帮忙迁移）。
- size 用 baseCount + CounterCell 数组分散计数（类似 LongAdder），避免热点。

为什么不允许 null key / value：并发场景下无法区分「key 不存在」和「key 存在但值是 null」，会带来二义性。

其他并发容器：CopyOnWriteArrayList（写时复制，适合读多写极少，如白名单、监听器列表）、BlockingQueue 家族、ConcurrentLinkedQueue（无锁）。

## collections-hashmap | HashMap 扩容与红黑树

JDK8 结构：数组 + 链表 + 红黑树。

put 流程：计算 hash（h = key.hashCode() ^ (h >>> 16)，让高位参与运算，减少碰撞）→ 定位桶 → 空桶直接放 → 有值则比较 hash 和 equals → 相同则覆盖 → 不同则尾插（JDK7 是头插，多线程扩容会形成环）→ 链表长度 ≥ 8 且容量 ≥ 64 时转红黑树。

扩容：容量和阈值都翻倍，默认容量 16、负载因子 0.75。JDK8 扩容时不需要重新计算 hash，因为容量是 2 的幂，元素要么留在原位置，要么移到「原位置 + 旧容量」—— 用 hash & oldCap 判断，结果为 0 留原位，否则移到高位。

为什么容量必须是 2 的幂：`(n - 1) & hash` 等价于取模但更快，且容量为 2 的幂时 (n-1) 的二进制全为 1，散列更均匀。所以指定初始容量时应该传「预期元素数 / 0.75 + 1」并向上取整到 2 的幂。

红黑树条件：链表长度超过 8 且数组长度 ≥ 64（否则先扩容而不是转树），退化阈值为 6（留有缓冲，避免频繁转换）。

线程不安全的表现：JDK7 并发扩容产生环形链表导致 get 死循环（CPU 100%）；JDK8 会出现数据覆盖丢失。

## mysql-index | 索引结构与 B+ 树

InnoDB 用 B+ 树：非叶子节点只存键值不存数据（所以单页能放更多键，树更矮，通常 3 层就能支撑上千万行）、叶子节点存数据并用双向链表串起来（范围查询快）、所有数据都在叶子节点（查询稳定，都要走到叶子）。

为什么不用 B 树：B 树非叶子节点也存数据，单页存的键变少，树更高，磁盘 IO 次数更多；且范围查询要中序遍历，不如链表快。

为什么不用哈希索引：哈希只支持等值查询，不支持范围、排序、最左前缀；InnoDB 只有自适应哈希索引（内部自动为热点页建）。

聚簇索引与二级索引：主键索引即数据本身（叶子节点存整行），二级索引叶子存主键值，所以二级索引查询要回表（除非覆盖索引）。

索引类型：
- 主键索引（聚簇）、唯一索引、普通索引、联合索引、前缀索引（对长字符串取前 N 个字符）。
- 覆盖索引：查询的列都在索引里，Extra 显示 Using index，不需要回表。
- 索引下推（ICP，MySQL 5.6+）：把 where 条件中能用索引的列下推到存储引擎层过滤，减少回表次数。

联合索引与最左前缀：索引 (a,b,c) 相当于建了 (a)、(a,b)、(a,b,c) 三个索引，但不支持 (b)、(b,c)。范围为 range 时，其后的列无法再用索引排序。

## mysql-index-fail | 索引失效场景

1. 违反最左前缀：联合索引 (a,b,c)，where b=1 或 where c=1 不走索引。
2. 索引列上做函数或运算：`where YEAR(create_time) = 2024`、`where id + 1 = 10`。改写为范围查询：`create_time >= '2024-01-01' and create_time < '2025-01-01'`。
3. 隐式类型转换：字符串列用数字查（`where phone = 13800000000`，phone 是 varchar）会做全表扫描；反过来数字列用字符串查可以走索引。
4. like 以通配符开头：`like '%abc'` 失效，`like 'abc%'` 可以。
5. OR 连接的条件中有列没有索引：整体退化为全表扫描，可以用 union all 改写。
6. 使用 != 、not in、is not null 有时失效（优化器判断全表更快时）。
7. 优化器认为全表扫描更快：表很小、或者列的区分度太低（如性别字段，只有两三个值）。
8. 字符集或排序规则不一致的 join：两张表字符集不同会导致索引失效。

注意：`explain` 的 type 字段按效率排序 system > const > eq_ref > ref > range > index > ALL，至少要到 range；Extra 里出现 Using filesort / Using temporary 需要优化。

## mysql-mvcc | 事务隔离级别与 MVCC

四种隔离级别：读未提交（脏读）、读已提交（RC，不可重复读）、可重复读（RR，MySQL 默认，幻读）、串行化（加锁，无并发问题但性能差）。

MySQL 的 RR 在大多数场景已解决幻读：快照读靠 MVCC，当前读靠 Next-Key Lock（记录锁 + 间隙锁）。

MVCC 实现三要素：
1. 隐藏字段：每行有 DB_TRX_ID（最近修改的事务 ID）、DB_ROLL_PTR（回滚指针，指向 undo log 里的旧版本）。
2. undo log 版本链：每次修改都把旧值写入 undo log，通过回滚指针串成链表。
3. ReadView：记录生成快照时活跃的事务 ID 列表（m_ids）、最小活跃事务 ID（min_trx_id）、下一个要分配的 ID（max_trx_id）。判断规则：trx_id < min_trx_id 可见；trx_id >= max_trx_id 不可见；在 m_ids 中不可见；不在则可见。不可见就顺着版本链往前找。

RC 与 RR 的区别只在 ReadView 的生成时机：RC 每次 select 都重新生成（所以能看到别人已提交的新数据），RR 只在第一次 select 时生成并复用（所以整个事务看到的是同一个快照）。

当前读（select ... for update / lock in share mode / update / delete / insert）总是读最新版本并加锁。

## mysql-lock | 锁机制与死锁

按粒度：表锁（开销小、并发低）、行锁（InnoDB 支持，作用在索引上）、页锁。

行锁的三种形式：
- Record Lock：锁单条索引记录。
- Gap Lock：锁索引记录之间的间隙，防止插入，只在 RR 下生效。
- Next-Key Lock：Record + Gap，左开右闭区间 (a, b]，RR 下默认，用来防幻读。

关键点：**InnoDB 的行锁是加在索引上的**，如果 where 条件没有用到索引，会退化成锁全表所有记录（看似表锁）。所以更新语句一定要走索引。

死锁成因：两个事务以相反顺序持锁再请求对方的锁。示例：事务 A 更新 id=1 再更新 id=2，事务 B 更新 id=2 再更新 id=1。

减少死锁：
1. 按固定顺序访问资源（如统一按主键升序更新）。
2. 缩短事务，把耗时操作（RPC、文件 IO）放到事务外。
3. 更新语句走索引，避免锁范围扩大。
4. 设置 innodb_lock_wait_timeout，开启 innodb_deadlock_detect（默认开，发现死锁回滚代价小的事务）。

排查：`show engine innodb status` 看 LATEST DETECTED DEADLOCK 段落；`select * from performance_schema.data_locks` 看当前锁等待。

乐观锁 vs 悲观锁：乐观锁用版本号 CAS（`update t set v = v + 1 where id = ? and v = ?`），适合冲突少的场景；悲观锁 select for update，适合冲突激烈的场景。

## mysql-slow | 慢查询定位与 SQL 优化

开启慢查询日志：slow_query_log = ON、long_query_time = 1、slow_query_log_file，或用 performance_schema。分析用 mysqldumpslow 或 pt-query-digest 按耗时排序。

EXPLAIN 关键列：
- type：访问类型，至少要到 range，出现 ALL 说明全表扫描。
- key：实际使用的索引；possible_keys 是可能用的。
- rows：预估扫描行数，越小越好。
- filtered：过滤后剩余百分比。
- Extra：Using index（覆盖索引，好）、Using where、Using filesort（需要额外排序，要优化）、Using temporary（用了临时表，常见于 group by / distinct）。

优化手段：
1. 加合适的联合索引，遵循最左前缀，把区分度高的列放前面。
2. 覆盖索引避免回表。
3. 深分页优化：`limit 1000000, 10` 改为 `where id > 上一页最大 id limit 10`（游标法），或者延迟关联（先用覆盖索引取出主键再 join 回表）。
4. 避免 `select *`，只取需要的列。
5. 大事务拆分、批量插入用 `insert into ... values (...),(...)` 或 `load data`。
6. 用 join 代替子查询（MySQL 对子查询优化较差，尤其 5.6 之前）。
7. 分页 count 慢时，可以用近似值或维护计数表。

## redis-struct | Redis 数据结构与底层编码

五种基本类型与底层编码：

- String：int（整数）、embstr（≤44 字节的短字符串，一次内存分配）、raw（长字符串）。用途：缓存对象、计数器（incr）、分布式锁、分布式 ID（incrby）。
- Hash：listpack（旧版 ziplist，字段少且值小时）→ hashtable。用途：存对象，比 String 存 JSON 更省内存且能单字段更新。
- List：quicklist（多个 listpack 组成的双向链表），3.2 之前是 ziplist + linkedlist。用途：栈 / 队列、`lpush + brpop` 简易消息队列。
- Set：intset（全是整数且元素少）→ hashtable。用途：去重、抽奖、共同关注（sinter）、点赞。
- ZSet：listpack → skiplist + hashtable。用途：排行榜、延迟队列、滑动窗口限流。

特殊类型：
- HyperLogLog：基数估算，12KB 估算 2^64 个元素，误差 0.81%，用于 UV 统计。
- Bitmap：位图，用于签到、用户活跃标记。
- GEO：地理位置，底层是 ZSet + geohash。
- Stream：消息流，支持消费者组，可替代部分 MQ 场景。

内存优化：设置 hash-max-listpack-entries / -value、zset-max-listpack-entries 等阈值，短小结构会自动用紧凑编码；大 key 要拆分。

## redis-zset | ZSet 与跳表

ZSet 底层是**跳表（skiplist）+ 哈希表**的组合：
- 哈希表：member → score，O(1) 查找某个成员的分数。
- 跳表：按 score 有序排列，支持 O(log N) 的插入、删除、范围查询和排名。

常见命令：zadd、zscore、zrank（升序排名）、zrevrank（降序）、zrangebyscore、zrange、zincrby。

为什么用跳表而不是红黑树：
1. **范围查询更友好**：跳表底层是有序链表 + 多层索引，找起点后顺序遍历即可；红黑树需要中序遍历或复杂的后继查找。
2. **实现简单**：跳表插入删除只需维护多层指针，不需要旋转和再平衡（红黑树插入删除有大量分情况讨论），代码更易维护、更少 bug。
3. **并发友好**：跳表局部修改，锁的范围小（虽然 Redis 单线程，但设计思想如此）；红黑树再平衡会影响较大子树。
4. **内存可控**：层高用随机函数（1/4 概率升层，最大 32 层）决定，平衡性接近平衡树但不需要存储平衡信息。

跳表查找过程：从最高层开始，若本层下一个节点的 score 小于目标就前进，否则下降一层，直到找到或到最底层。平均时间复杂度 O(log N)。

典型应用：排行榜（zrevrange 取前 10）、延时队列（score 存执行时间戳，定时 zrangebyscore 取到期任务）、滑动窗口限流（zadd 时间戳，zremrangebyscore 清理过期，zcard 计数）。

## redis-cache | 缓存穿透、击穿、雪崩

缓存穿透：查询一个数据库里也不存在的 key，缓存不命中，每次请求都打到 DB。恶意攻击常用不存在的 ID 刷接口。
- 解法一：布隆过滤器（前置拦截，注意有误判率且**元素无法删除**，可用 Counting Bloom 或定期重建）。
- 解法二：把空结果也缓存起来（值设短 TTL，比如 60s），防止同一 key 反复穿透。
- 解法三：参数校验，ID ≤ 0 直接拒绝。

缓存击穿：某个热点 key 过期的瞬间，大量并发请求同时穿透到 DB 去重建缓存。
- 解法一：互斥锁 / 分布式锁，只让一个线程去查 DB 并回填，其他线程短暂等待后读缓存。
- 解法二：逻辑过期——value 里存逻辑过期时间，不设 Redis TTL，发现逻辑过期后异步重建，返回旧值兜底。
- 解法三：热点数据预热 + 永不过期。

缓存雪崩：大量 key 在同一时刻集体过期，或者 Redis 实例宕机，导致请求全部打到 DB。
- 解法一：TTL 加随机值（如基础 30 分钟 + 随机 0~5 分钟），打散过期时间。
- 解法二：多级缓存（本地 Caffeine + Redis），Redis 挂了还有本地兜底。
- 解法三：Redis 高可用（哨兵 / Cluster）、熔断降级（Sentinel / Hystrix 对 DB 限流，保护数据库不被打死）。

一致性：可接受的方案是"先更新 DB 再删除缓存"（Cache Aside），配合延迟双删或订阅 binlog（Canal）异步删缓存。**先删缓存再更新 DB 会导致并发下读到旧值**。

## redis-persist | RDB 与 AOF 持久化

RDB：把内存快照以二进制写入 dump.rdb。
- 触发方式：save（阻塞主线程，生产禁用）、bgsave（fork 子进程，写时复制 COW）、配置 save 900 1 等自动触发、shutdown 时、主从全量同步时。
- 优点：文件紧凑、恢复快、适合备份与灾备。
- 缺点：快照间隔内宕机会丢数据；fork 时若内存大，复制页表本身也可能阻塞（几十 GB 实例 fork 可能耗时数百毫秒）。

AOF：追加写命令日志，appendonly yes 开启。
- 刷盘策略（appendfsync）：always（每条命令都 fsync，最安全、性能最差）、everysec（每秒 fsync，默认，最多丢 1 秒）、no（交给操作系统，最不安全）。
- AOF 重写：命令日志会越来越大，用 bgrewriteaof 生成最小命令集（如 100 次 incr 重写成一次 set 100）。重写期间新命令写入 AOF 重写缓冲区，完成后追加，保证不丢。
- 优点：数据更安全，丢失窗口小。
- 缺点：文件大、恢复慢（要重放所有命令）。

混合持久化（Redis 4.0+）：AOF 重写时，前半部分用 RDB 格式存全量快照，后半部分追加增量命令。兼顾恢复速度和数据安全。

实践建议：主库开 AOF（everysec）+ 定期 RDB 备份；从库只开 RDB 做备份。**持久化不能替代主从和高可用**，Redis 本身定位是缓存，重要数据要靠 DB 兜底。

## redis-lock | 分布式锁

基于 Redis 的分布式锁，正确加锁：
```
SET lock:resource <唯一值如 UUID> NX PX 30000
```
必须同时满足 NX（不存在才设置，保证互斥）和 PX（自动过期，防死锁），value 用唯一值标识持有者。

解锁必须用 Lua 脚本保证"判断是自己的锁"和"删除锁"的原子性：
```
if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end
```
不能先 get 再 del（中间锁可能已过期并被别人持有）。

三个经典问题：
1. **锁过期但业务没执行完** —— 看门狗续期（Redisson 的 watch dog 默认每 10 秒续到 30 秒）。注意：只有不指定 leaseTime 时才启用看门狗。
2. **主从切换丢锁** —— 主节点加锁成功但还没同步到从节点就宕机，从节点升主后另一个线程能拿到锁。解决：Redlock（向多数节点加锁，但要考虑时钟漂移，争议较大）或改用 ZooKeeper / etcd（基于 ZAB/Raft 一致性协议）。
3. **不可重入** —— Redis 锁默认不可重入，Redisson 用 Hash 结构（key → {线程标识: 重入次数}）实现重入。

Redisson 提供的锁：RLock（可重入）、RReadWriteLock（读写锁）、RSemaphore、RCountDownLatch。

什么时候不该用 Redis 锁：能靠数据库唯一索引、乐观锁版本号、状态机解决的场景，优先用 DB 层面的方案，比分布式锁简单可靠。

## spring-ioc | IOC 与循环依赖

IOC 容器启动流程：加载配置（XML / 注解 / Java Config）→ 解析成 BeanDefinition → 注册到 BeanDefinitionRegistry → 实例化（refresh 的 finishBeanFactoryInitialization）→ 属性填充（依赖注入）→ 初始化（Aware 接口、BeanPostProcessor 前置、init-method、BeanPostProcessor 后置，AOP 代理在此生成）→ 放入单例池。

三级缓存解决循环依赖：
- 一级缓存 singletonObjects：完整的成品 Bean。
- 二级缓存 earlySingletonObjects：提前暴露的半成品（已实例化但未填充属性）。
- 三级缓存 singletonFactories：ObjectFactory 工厂，用于生成早期引用（如果需要 AOP，这里返回的是代理对象）。

流程（A 依赖 B，B 依赖 A）：A 实例化后把工厂放入三级缓存 → 填充 B → B 实例化后填充 A → 从三级缓存拿到 A 的早期引用（并移入二级缓存）→ B 完成 → A 完成。

为什么需要三级而不是两级：如果只有二级缓存且发生 AOP，那么每次拿到的早期引用可能是原始对象而非代理对象，会导致注入的对象与最终 Bean 不一致。三级缓存的工厂保证了「需要代理时只创建一次代理」。

无法解决的场景：
1. **构造器注入的循环依赖** —— 实例化阶段就卡住，抛 BeanCurrentlyInCreationException。可以用 @Lazy 解决。
2. **prototype 作用域的循环依赖** —— 不缓存，无法提前暴露。
3. @Async 方法导致的循环依赖 —— 因为异步代理在初始化后才生成。

## spring-aop | AOP 与动态代理

AOP 术语：切面 Aspect、连接点 JoinPoint、切点 Pointcut、通知 Advice（Before / After / AfterReturning / AfterThrowing / Around）、织入 Weaving。

两种动态代理：
- **JDK 动态代理**：要求目标类实现接口，运行时生成实现同一接口的代理类（Proxy + InvocationHandler）。
- **CGLIB**：通过继承目标类生成子类并覆写方法，无需接口，但无法代理 final 类和 final / private 方法。

Spring Boot 2.x 起默认 proxy-target-class=true，即统一用 CGLIB（Spring 4.3 起 CGLIB 已内置，不需要额外依赖）。

切点表达式：`execution(* com.xxx.service..*.*(..))`、`@annotation(...)`、`within(...)`、`@within(...)`。

典型应用：事务（@Transactional）、日志、权限校验、缓存、幂等、分布式锁、接口耗时统计。

AOP 失效场景：
1. **同类内部方法调用** —— `this.methodB()` 不走代理，@Transactional / @Cacheable 都失效。解决：注入自身、用 AopContext.currentProxy()、拆到另一个 Bean。
2. **private / final 方法** —— CGLIB 无法覆写。
3. **对象不是 Spring 管理的** —— 自己 new 出来的对象没有代理。
4. 切点表达式写错，或者方法被 static 修饰。

## spring-tx | 事务失效与传播行为

@Transactional 失效的八种场景：
1. **同类内部方法直接调用**（最常见）—— 未走代理。
2. **方法不是 public** —— Spring 的 TransactionInterceptor 只对 public 方法生效（CGLIB 无法覆写 private/final）。
3. **异常被 catch 没抛出** —— 默认只对 RuntimeException 和 Error 回滚。
4. **抛的是检查型异常** —— 需要 `@Transactional(rollbackFor = Exception.class)`。
5. **类没有被 Spring 管理** —— 没有 @Service / @Component。
6. **多线程调用** —— 事务存在 ThreadLocal 里，子线程的事务和主线程不是一个。
7. **数据库引擎不支持事务** —— MyISAM 不支持。
8. **传播行为设置成 NOT_SUPPORTED / NEVER**，或者自己把事务挂起了。

七种传播行为：
- REQUIRED（默认）：有事务就加入，没有就新建。
- REQUIRES_NEW：总是新建，原事务挂起；注意它会开新连接，如果外层事务持有行锁，容易死锁。
- NESTED：嵌套事务，基于 savepoint，内层回滚不影响外层（但外层回滚会带上内层）。
- SUPPORTS / NOT_SUPPORTED / MANDATORY / NEVER：按字面理解。

事务与锁：事务没提交前行锁不释放，所以不要把 RPC 调用、文件操作、大批量循环放在事务里，会显著拉长持锁时间。

## spring-mvc | Spring MVC 请求处理流程

一次请求的完整链路：
1. 请求到达 DispatcherServlet（前端控制器），它从 HandlerMapping 找到匹配的 Handler（Controller 方法）和拦截器链。
2. 依次执行 HandlerInterceptor 的 preHandle。
3. 通过 HandlerAdapter 调用 Controller 方法（适配不同签名，如 @RequestParam / @RequestBody 的参数解析由 HandlerMethodArgumentResolver 完成）。
4. 返回值经 HandlerMethodReturnValueHandler 处理：@ResponseBody 由 RequestResponseBodyMethodProcessor 用 HttpMessageConverter（Jackson）序列化成 JSON。
5. 执行 postHandle，再执行 afterCompletion（视图渲染完成后）。
6. 异常由 HandlerExceptionResolver 处理，@ControllerAdvice + @ExceptionHandler 就是通过 ExceptionHandlerExceptionResolver 生效的。

常用注解：@RestController（= @Controller + @ResponseBody）、@RequestMapping / @GetMapping、@PathVariable、@RequestParam、@RequestBody、@RequestPart。

参数绑定与校验：@Valid / @Validated 触发 JSR-303 校验，配合 @NotNull / @Size / @Pattern；校验失败抛 MethodArgumentNotValidException，用 @ControllerAdvice 统一转成业务错误码。

统一异常处理要注意：@ExceptionHandler 对「Filter 中抛出的异常」不生效（Filter 在 DispatcherServlet 之外），需要在 Filter 里自己 try-catch 或改用 HandlerExceptionResolver。

## dist-tx | 分布式事务

方案对比：
1. **2PC / XA**：准备 + 提交两阶段，强一致但同步阻塞、协调者单点、数据库要支持 XA，生产很少用。
2. **TCC**（Try-Confirm-Cancel）：业务层面补偿，需要为每个操作实现三个接口，侵入性大但性能好；要处理空回滚、幂等、悬挂三个问题。
3. **本地消息表**：把消息和业务操作放在同一个本地事务里写库，再异步投递，靠重试保证最终一致。简单可靠，是中小项目的首选。
4. **事务消息**（RocketMQ）：半消息 + 回查，本质也是最终一致。
5. **Saga**：长事务拆成多个本地事务，失败时反向补偿。适合流程长、可补偿的业务（如订单 → 库存 → 支付 → 积分）。
6. **最大努力通知**：只保证尽力投递，适合对账类场景。

选择原则：**能不用分布式事务就不用**。优先通过业务设计规避（比如把跨库操作改成同库、用状态机 + 定时任务补偿、让操作幂等从而支持重试）。

最终一致的兜底手段：定时任务扫描异常状态、对账系统（T+1 核对）、人工介入入口。

## dist-id | 分布式 ID 与分库分表

分布式 ID 方案：
- UUID：本地生成性能好，但是字符串占空间、无序（作为 InnoDB 主键会导致页分裂严重，性能差）。
- 数据库自增：简单，但单点瓶颈、分库后冲突。
- 号段模式（美团 Leaf）：一次取一批 ID 缓存在内存，减少 DB 压力。
- 雪花算法（Snowflake）：64 位 = 1 位符号 + 41 位时间戳（约 69 年）+ 10 位机器 ID + 12 位序列号（每毫秒 4096 个）。**依赖系统时钟，回拨要处理**（等待、报错或用备用位）。
- Redis incr：性能好，但依赖 Redis 可用性。

分库分表：
1. 先问"能不能不分"：优化 SQL 和索引 → 读写分离 → 归档冷数据 → 加缓存 → 最后才考虑分片。
2. 分片键选择：以最高频查询条件为准（用户维度用 user_id，订单维度用 order_id）。要避免 90% 的查询都要全路由。
3. 分片算法：hash 取模（分布均匀但扩容要迁移）、range（易扩容但可能热点）、一致性哈希（扩容影响小）、基因法（把 user_id 的低几位编进 order_id，实现从订单直接路由到用户分片）。
4. 分片数是 2 的幂，方便双倍扩容：从 4 扩到 8 时，每个旧分片的数据一半留下、一半迁到新分片。
5. 必须一起解决的麻烦：全局唯一 ID、跨分片查询/排序/分页（走 ES 或冗余表）、分布式事务、扩容时的双写与数据校验、热点（大商户单拆）。

中间件：ShardingSphere-JDBC（客户端，无代理）、MyCat（代理层）。

## ai-rag | RAG 切分策略与检索

切分（Chunking）决定了检索质量的上限，比换 embedding 模型影响更大。

切分顺序（实测有效）：
1. **先按 Markdown 标题切**，保证一个 chunk 只讲一个主题。这一步最关键——按固定长度硬切会把多个主题混进同一段，检索时严重串味。
2. 标题内再按段落合并到目标长度（如 600 字）。
3. 单段仍超长才用滑窗切分，重叠 10%~20%（如 600 字块重叠 100 字），避免边界处的语义被切断。
4. 代码块内部不切分（否则语法不完整，embedding 也会被污染）。

为什么"标题优先"优于"等长切分"：等长切分实现简单，但一个 600 字段落里可能同时出现 Redis、MySQL、JVM 三个主题，向量是所有主题的平均，检索任何一个主题都会命中它，导致 Top1 经常是错的。

检索阶段的工程点：
- topK 一般取 3~5，太少召回不全，太多引入噪声干扰 LLM。
- **相似度阈值要谨慎**：实测发现负样本（语料里完全没有的主题）也能拿到 50% 左右的 cosine 相似度，与正确命中的 57%~79% 区间有重叠，所以用绝对阈值过滤会产生大量假阳性；应以排序指标（Top1/Top3 命中）作为验收标准。
- 混合检索（向量 + 关键词 BM25）能补足向量对专有名词、数字、代码标识符不敏感的问题。
- 重排序（Rerank）：先召回 top20 再用 cross-encoder 精排取 top5，能明显提升精度但增加延迟。

## ai-agent | Function Calling 与工具编排

Function Calling 的本质：把工具描述（名称、用途、参数 schema）作为结构化信息随 prompt 一起发给模型，模型返回"要调用哪个函数、参数是什么"的结构化结果，由应用侧执行后把结果回填，再继续对话。

Spring AI 的实现：@Tool 注解标注方法，@ToolParam 描述参数，把工具对象注册到 ChatClient，框架自动完成 schema 生成、调用解析、结果回填、循环控制。

工程要点：
1. **工具描述即 prompt**：描述要写清"什么时候用、什么时候不用、参数含义"，模型选错工具多半是描述没写清。
2. **工具数量要克制**：3~5 个为宜，太多会导致选择困难、上下文膨胀、调用成本上升。
3. **参数校验必须做**：模型可能给出不存在的 ID、超范围的数值，工具内部要校验并返回明确错误（让模型能自我纠正，而不是抛 500）。
4. **幂等与超时**：工具可能被重复调用，写操作要幂等；外部调用要设超时，避免卡死整个对话。
5. **可观测**：记录每次调用的工具名、参数、耗时、结果，既用于排错也用于成本分析。开 DEBUG 日志能看到 "Executing tool call: xxx"。
6. **失败降级**：工具调用不稳定（模型偶发不返回 tool_calls）时要有兜底路径，不能"点了没反应"。

Agent 与单轮工具调用的区别：Agent 需要自主规划（多步拆解）、反思（结果不好时重试或换策略）、记忆（跨轮次保留状态）、以及终止条件控制（最大步数、预算上限），否则容易死循环或成本失控。

单轮问答只做一次检索/API 调用，可控性强，是绝大多数 RAG 应用的合理形态。
