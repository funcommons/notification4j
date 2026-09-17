# legacy-reference（脱离编译，仅作实现参考）

benefit4j 权益域实现，裁剪期整体移出编译。已按裁剪规则统一改名（**非原始命名**）：
`Benefit*→Nfy*`、`fun.commons.benefit4j→fun.commons.notification4j`、`ubma_/ubmp_→nfya_/nfyp_`。
故文中 `NfyaNfySet/NfyaNfyItemMapper` 等类名为改名产物（原名 `UbmaBenefitSet/UbmaBenefitItemMapper`），
URL 仍保留 `/benefit/api/v1/...` 原样。重建 notification 域时参考其双模式 client/信封/缓存注解用法即可，
表名/接口路径一律以 documents/ 定稿文档为准。
