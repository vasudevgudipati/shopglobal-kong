package com.shopglobal.web;

import com.shopglobal.web.ChatModels.ChatResponse;
import com.shopglobal.web.ChatModels.Session;

/** The shopping assistant. The only implementation is RemoteAgent: the website calls the shopglobal-agent service (LLM through Kong). */
public interface Agent {

    /** language is "en" or "hi" - chosen by the customer in the chat window only. */
    ChatResponse handle(Session session, String userText, String language);

    /** Called when the customer presses Confirm or Cancel on a pending write action. */
    ChatResponse confirm(Session session, String actionId, boolean approve, String language);
}
