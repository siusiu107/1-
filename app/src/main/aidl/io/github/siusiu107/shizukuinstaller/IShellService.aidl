package io.github.siusiu107.shizukuinstaller;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

// Shizuku UserService: shell(2000) 또는 root(0) 권한으로 실행되는 별도 프로세스
interface IShellService {
    // Shizuku 가 서비스를 종료할 때 호출하는 고정 트랜잭션 코드
    void destroy() = 16777114;

    // cmd 를 실행하고 {"code": Int, "output": String} 를 돌려줌. stdin 이 있으면 그 내용을 표준입력으로 흘려보냄.
    Bundle run(in String[] cmd, in ParcelFileDescriptor stdin) = 1;
}
