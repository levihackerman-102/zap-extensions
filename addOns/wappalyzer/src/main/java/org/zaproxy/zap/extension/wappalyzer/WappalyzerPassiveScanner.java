/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2013 The ZAP Development Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.zaproxy.zap.extension.wappalyzer;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.htmlparser.jericho.Element;
import net.htmlparser.jericho.HTMLElementName;
import net.htmlparser.jericho.Source;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.model.HistoryReference;
import org.parosproxy.paros.model.SiteNode;
import org.parosproxy.paros.network.HtmlParameter;
import org.parosproxy.paros.network.HttpMessage;
import org.zaproxy.addon.commonlib.ResourceIdentificationUtils;
import org.zaproxy.zap.extension.pscan.PassiveScanThread;
import org.zaproxy.zap.extension.pscan.PassiveScanner;
import org.zaproxy.zap.extension.pscan.PluginPassiveScanner;

public class WappalyzerPassiveScanner implements PassiveScanner {

    private static final Logger LOGGER = LogManager.getLogger(WappalyzerPassiveScanner.class);
    private WappalyzerApplicationHolder applicationHolder;
    private Set<String> visitedSiteIdentifiers = new HashSet<>();
    private ApplicationMatch appMatch;
    private Application currentApp;
    private volatile boolean enabled = true;

    public WappalyzerPassiveScanner(WappalyzerApplicationHolder applicationHolder) {
        super();
        this.applicationHolder = applicationHolder;
    }

    @Override
    public String getName() {
        return Constant.messages.getString("wappalyzer.scanner");
    }

    @Override
    public void scanHttpResponseReceive(HttpMessage msg, int id, Source source) {
        String siteIdentifier = getSiteIdentifier(msg);

        if (!visitedSiteIdentifiers.add(siteIdentifier)) {
            return;
        }

        long startTime = System.currentTimeMillis();
        for (Application app : this.getApps()) {
            this.currentApp = app;
            checkAppMatches(msg, source);
            if (appMatch != null) {
                String site = ExtensionWappalyzer.normalizeSite(msg.getRequestHeader().getURI());
                LOGGER.debug("Adding {} to {}", app.getName(), site);
                addApplicationsToSite(site, appMatch);
                this.appMatch = null;
            }
            this.currentApp = null;
        }

        LOGGER.debug("Analysis took {} ms", System.currentTimeMillis() - startTime);
    }

    private String getSiteIdentifier(HttpMessage msg) {
        SiteNode node = getSiteNode(msg);
        if (node != null) {
            return node.getHierarchicNodeName() + "_" + node.getNodeName();
        }
        return msg.getRequestHeader().getURI().toString();
    }

    private SiteNode getSiteNode(HttpMessage msg) {
        HistoryReference href = msg.getHistoryRef();
        if (href == null) {
            return null;
        }
        return href.getSiteNode();
    }

    private void addApplicationsToSite(String site, ApplicationMatch applicationMatch) {
        applicationHolder.addApplicationsToSite(site, applicationMatch);
        // Add implied apps
        for (String imp : applicationMatch.getApplication().getImplies()) {
            Application ia = applicationHolder.getApplication(imp);
            if (ia != null) {
                addApplicationsToSite(site, new ApplicationMatch(ia));
            }
        }
    }

    private void checkAppMatches(HttpMessage msg, Source source) {
        checkUrlMatches(msg);
        checkHeadersMatches(msg);
        checkCookieMatches(msg);
        if (!msg.getResponseHeader().isText()) {
            return; // Don't check body if not text'ish
        }
        checkBodyMatches(msg);
        checkMetaElementsMatches(source);
        checkScriptElementsMatches(source);
        checkCssElementsMatches(msg, source);
        checkSimpleDomMatches(msg);
        checkDomElementMatches(msg);
    }

    private void checkCssElementsMatches(HttpMessage msg, Source source) {
        for (AppPattern appPattern : currentApp.getCss()) {
            if (ResourceIdentificationUtils.isCss(msg)) {
                addIfMatches(appPattern, msg.getResponseBody().toString());
            } else {
                for (Element styleElement : source.getAllElements(HTMLElementName.STYLE)) {
                    addIfMatches(appPattern, styleElement.getSource().toString());
                }
            }
        }
    }

    private void checkScriptElementsMatches(Source source) {
        for (Element scriptElement : source.getAllElements(HTMLElementName.SCRIPT)) {
            for (AppPattern appPattern : currentApp.getScript()) {
                String src = scriptElement.getAttributeValue("src");
                if (src != null && !src.isEmpty()) {
                    addIfMatches(appPattern, src);
                }
            }
        }
    }

    private void checkMetaElementsMatches(Source source) {
        List<Element> metaElements = source.getAllElements(HTMLElementName.META);
        for (Element metaElement : metaElements) {
            for (Map<String, AppPattern> sp : currentApp.getMetas()) {
                for (Map.Entry<String, AppPattern> entry : sp.entrySet()) {
                    String name = metaElement.getAttributeValue("name");
                    String content = metaElement.getAttributeValue("content");
                    if (name != null && content != null && name.equals(entry.getKey())) {
                        AppPattern p = entry.getValue();
                        addIfMatches(p, content);
                    }
                }
            }
        }
    }

    private void checkDomElementMatches(HttpMessage message) {
        if (!message.getResponseHeader().isHtml()) {
            return;
        }
        Document doc = Jsoup.parse(message.getResponseBody().toString());
        for (Map<String, Map<String, Map<String, AppPattern>>> domSelectorMap :
                currentApp.getDom()) {
            for (Map.Entry<String, Map<String, Map<String, AppPattern>>> selectorMap :
                    domSelectorMap.entrySet()) {
                for (Map.Entry<String, Map<String, AppPattern>> nodeSelectorMap :
                        selectorMap.getValue().entrySet()) {
                    for (Map.Entry<String, AppPattern> value :
                            nodeSelectorMap.getValue().entrySet()) {
                        Elements selectedElements = doc.select(selectorMap.getKey());
                        for (org.jsoup.nodes.Element selectedElement : selectedElements) {
                            if (Objects.equals(value.getKey(), "text")) {
                                AppPattern ap = value.getValue();
                                addIfMatches(ap, selectedElement.text());
                            }
                            if (Objects.equals(nodeSelectorMap.getKey(), "attributes")) {
                                AppPattern ap = value.getValue();
                                if (selectedElement.hasAttr(value.getKey())) {
                                    addIfMatches(ap, selectedElement.attr(value.getKey()));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void checkSimpleDomMatches(HttpMessage msg) {
        String body = msg.getResponseBody().toString();
        for (String selector : currentApp.getSimpleDom()) {
            addIfDomMatches(selector, body);
        }
    }

    private void checkBodyMatches(HttpMessage msg) {
        String body = msg.getResponseBody().toString();
        for (AppPattern p : currentApp.getHtml()) {
            addIfMatches(p, body);
        }
    }

    private void checkHeadersMatches(HttpMessage msg) {
        for (Map<String, AppPattern> sp : currentApp.getHeaders()) {
            for (Map.Entry<String, AppPattern> entry : sp.entrySet()) {
                String header = msg.getResponseHeader().getHeader(entry.getKey());
                if (header != null) {
                    AppPattern p = entry.getValue();
                    addIfMatches(p, header);
                }
            }
        }
    }

    private void checkCookieMatches(HttpMessage msg) {
        for (Map<String, AppPattern> sp : currentApp.getCookies()) {
            for (Map.Entry<String, AppPattern> entry : sp.entrySet()) {
                for (HtmlParameter cookie : msg.getCookieParams()) {
                    if (entry.getKey().equals(cookie.getName())) {
                        AppPattern p = entry.getValue();
                        addIfMatches(p, cookie.getValue());
                    }
                }
            }
        }
    }

    private void checkUrlMatches(HttpMessage msg) {
        String url = msg.getRequestHeader().getURI().toString();
        for (AppPattern p : currentApp.getUrl()) {
            addIfMatches(p, url);
        }
    }

    private void addIfDomMatches(String selector, String content) {
        Document doc = Jsoup.parse(content);
        Elements elements = doc.select(selector);
        if (!elements.isEmpty()) {
            this.appMatch = getAppMatch();
        }
    }

    private void addIfMatches(AppPattern appPattern, String content) {
        List<String> results = appPattern.findInString(content);
        if (results != null) {
            this.appMatch = getAppMatch();
            // TODO may need to account for the wappalyzer spec in dealing with version info:
            // https://www.wappalyzer.com/docs/specification
            results.forEach(appMatch::addVersion);
            LOGGER.debug(
                    "{} matched {}", appPattern.getType(), appMatch.getApplication().getName());
        }
    }

    private List<Application> getApps() {
        return applicationHolder.getApplications();
    }

    private ApplicationMatch getAppMatch() {
        if (appMatch == null) {
            appMatch = new ApplicationMatch(currentApp);
        }
        return appMatch;
    }

    @Override
    public void setParent(PassiveScanThread parent) {
        // Does not apply.
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean appliesToHistoryType(int historyType) {
        return PluginPassiveScanner.getDefaultHistoryTypes().contains(historyType);
    }
}
