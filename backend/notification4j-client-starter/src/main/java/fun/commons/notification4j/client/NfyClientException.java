package fun.commons.notification4j.client;

/** 门面业务异常：code=接口错误码（§6），local/remote 同构抛出 */
public class NfyClientException extends RuntimeException {

    private final int code;

    public NfyClientException(int code, String message) {
        super("[" + code + "] " + message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
