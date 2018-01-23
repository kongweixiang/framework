package com.kwxyzk.designPatterns.handler;

public abstract class Handler {

    private Handler nextHandler;

    /**
     * 每个处理者都必须对请求做出处理
     * @param request
     * @return
     */
    public final Response handleMessage(Request request) {
        Response response = null;
        if (this.getHandlerLever().equals(request.getRequestLevel())) {
            response = this.echo(request);
        } else {
            //判断是否还有下一个处理者
            if (this.nextHandler != null) {

                response = this.nextHandler.handleMessage(request);
            } else {
                //没有适当的处理者，业务自行处理
            }
        }
        return response;
    }

    public void setNextHandler(Handler nextHandler) {
        this.nextHandler = nextHandler;
    }

    /**
     * 每个处理都有一个处理级别
     * @return
     */
    protected abstract Level getHandlerLever();

    /**
     * 每个处理者都必须实现请求处理
     * @param request
     * @return
     */
    protected abstract Response echo(Request request);
}
