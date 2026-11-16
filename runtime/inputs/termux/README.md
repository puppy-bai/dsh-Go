# 固定 Termux 构建输入 / Fixed Termux build inputs

这两个原始 PRoot 包供运行时构建使用，版本保持 5.1.107.98。Termux 当前仓库已移除该版本；从下列官方历史构建恢复后，逐个核对了 `build-runtime.sh` 中既有的 SHA-256，未重新打包或修改内容。构建优先读取此目录，仍强制校验原 pin；不匹配立即失败。

These original PRoot packages retain version 5.1.107.98. After the live Termux repository removed this version, they were recovered from the official build below and verified against the existing SHA-256 pins in `build-runtime.sh`. Neither package was repacked or modified. Builds prefer these files and reject a checksum mismatch.

| File | Bytes | SHA-256 |
|---|---:|---|
| `proot_5.1.107.98_aarch64.deb` | 101288 | `29de40efea4daa2a6168b617915206511c63e8d46bf5f4b07d48723441ef7f3b` |
| `proot_5.1.107.98_x86_64.deb` | 111780 | `1c31ad7e1046f4d418fef19b6dfaa9a14801d2047ade6ad0375b3f5501e3e227` |

- Official build: <https://github.com/termux/termux-packages/actions/runs/39199950210>
- Build recipe: <https://github.com/termux/termux-packages/tree/9aedff9c28af3ca93c42eb2c3074221893232ae9/packages/proot>
- Official artifact IDs: `12163314230` (aarch64), `12164090536` (x86_64). Each artifact contains a tar archive; only its original `.deb` was extracted. The artifacts expire on 2027-01-17, so clean builds use these archived inputs.
- Corresponding source: <https://github.com/termux/proot/archive/refs/tags/v5.1.107.98.zip>
- Source archive SHA-256: `9de61bddd6f5016d065e2755b72ff4747290e5f992562f031785b107b51d02ba` (also pinned and checked by the runtime build).
- License: GPL-2.0; the original license text is in [COPYING](COPYING). See [THIRD_PARTY_NOTICES.md](../../../THIRD_PARTY_NOTICES.md) for the existing runtime modifications and source information.
