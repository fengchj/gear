package com.ftool.gear.client;

import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;

public class Message {
    private String to;
    private String from;
    private String msg;
    
    public Message(){
    }
    
    public Message(String to, String from, String msg) {
        super();
        this.to = to;
        this.from = from;
        this.msg = msg;
    }
    
    /**
     * @return the to
     */
    public String getTo() {
        return to;
    }
    /**
     * @param to the to to set
     */
    public void setTo(String to) {
        this.to = to;
    }
    /**
     * @return the from
     */
    public String getFrom() {
        return from;
    }
    /**
     * @param from the from to set
     */
    public void setFrom(String from) {
        this.from = from;
    }
    /**
     * @return the msg
     */
    public String getMsg() {
        return msg;
    }
    /**
     * @param msg the msg to set
     */
    public void setMsg(String msg) {
        this.msg = msg;
    }
    
    public String toString() {
        return ToStringBuilder.reflectionToString(this, ToStringStyle.SHORT_PREFIX_STYLE);
    }
}
