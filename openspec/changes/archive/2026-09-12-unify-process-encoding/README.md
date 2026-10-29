# unify-process-encoding

统一跨进程边界编码：stdio/子进程管道/日志/文件读写显式 UTF-8（Windows cmd.exe 子进程按 native.encoding 例外处理），.bat 脚本内容 ASCII 化并按平台编码写出
