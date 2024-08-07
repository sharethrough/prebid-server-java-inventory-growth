package org.prebid.server.auction.privacy.contextfactory;

import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Device;
import com.iab.openrtb.request.Site;
import io.vertx.core.Future;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.prebid.server.VertxTest;
import org.prebid.server.auction.IpAddressHelper;
import org.prebid.server.auction.model.AuctionContext;
import org.prebid.server.auction.model.TimeoutContext;
import org.prebid.server.geolocation.CountryCodeMapper;
import org.prebid.server.metric.MetricName;
import org.prebid.server.model.CaseInsensitiveMultiMap;
import org.prebid.server.model.HttpRequestContext;
import org.prebid.server.privacy.PrivacyExtractor;
import org.prebid.server.privacy.ccpa.Ccpa;
import org.prebid.server.privacy.gdpr.TcfDefinerService;
import org.prebid.server.privacy.gdpr.model.RequestLogInfo;
import org.prebid.server.privacy.gdpr.model.TcfContext;
import org.prebid.server.privacy.model.Privacy;
import org.prebid.server.settings.model.Account;

import java.util.ArrayList;
import java.util.function.UnaryOperator;

import static java.util.Collections.singletonList;
import static java.util.function.UnaryOperator.identity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class AuctionPrivacyContextFactoryTest extends VertxTest {

    @Mock
    private PrivacyExtractor privacyExtractor;
    @Mock
    private TcfDefinerService tcfDefinerService;
    @Mock(strictness = LENIENT)
    private IpAddressHelper ipAddressHelper;
    @Mock
    private CountryCodeMapper countryCodeMapper;

    private AuctionPrivacyContextFactory target;

    @BeforeEach
    public void setUp() {
        target = new AuctionPrivacyContextFactory(
                privacyExtractor,
                tcfDefinerService,
                ipAddressHelper,
                countryCodeMapper);
    }

    @Test
    public void contextFromShouldExtractInitialPrivacy() {
        // given
        final Privacy emptyPrivacy = Privacy.builder()
                .gdpr("")
                .consentString("")
                .ccpa(Ccpa.EMPTY)
                .coppa(0)
                .build();
        given(privacyExtractor.validPrivacyFrom(any(), any())).willReturn(emptyPrivacy);

        given(tcfDefinerService.resolveTcfContext(any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(Future.succeededFuture(TcfContext.empty()));

        final AuctionContext auctionContext = givenAuctionContext(
                context -> context.httpRequest(givenHttpRequestContext("invalid")));

        // when
        target.contextFrom(auctionContext);

        // then
        verify(privacyExtractor).validPrivacyFrom(any(), anyList());
    }

    @Test
    public void contextFromShouldAddTcfExtractionWarningsToAuctionDebugWarningsWhenInGdprScope() {
        // given
        final Privacy emptyPrivacy = Privacy.builder()
                .gdpr("")
                .consentString("")
                .ccpa(Ccpa.EMPTY)
                .coppa(0)
                .build();
        given(privacyExtractor.validPrivacyFrom(any(), any())).willReturn(emptyPrivacy);

        given(tcfDefinerService.resolveTcfContext(any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(Future.succeededFuture(TcfContext.builder().warnings(singletonList("Error")).build()));

        final AuctionContext auctionContext = givenAuctionContext(
                context -> context.httpRequest(givenHttpRequestContext(null)));

        // when
        target.contextFrom(auctionContext);

        // then
        assertThat(auctionContext.getDebugWarnings()).containsExactly("Error");
    }

    @Test
    public void contextFromShouldMaskIpV4WhenCoppaEqualsToOneAndIpV4Present() {
        // given
        final Privacy privacy = Privacy.builder()
                .gdpr("1")
                .consentString("consent_string")
                .ccpa(Ccpa.of("1YYY"))
                .coppa(1)
                .build();
        given(privacyExtractor.validPrivacyFrom(any(), any())).willReturn(privacy);

        given(ipAddressHelper.maskIpv4(anyString())).willReturn("maskedIpV4");
        given(ipAddressHelper.anonymizeIpv6(anyString())).willReturn("maskedIpV6");

        given(tcfDefinerService.resolveTcfContext(any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(Future.succeededFuture(TcfContext.empty()));

        final BidRequest bidRequest = givenBidRequest(request -> request.device(Device.builder().ip("ip").build()));
        final AuctionContext auctionContext = givenAuctionContext(context -> context
                .httpRequest(givenHttpRequestContext("invalid"))
                .bidRequest(bidRequest));

        // when
        target.contextFrom(auctionContext);

        // then
        verify(ipAddressHelper).maskIpv4(anyString());
    }

    @Test
    public void contextFromShouldMaskIpV6WhenCoppaEqualsToOneAndIpV6Present() {
        // given
        final Privacy privacy = Privacy.builder()
                .gdpr("1")
                .consentString("consent_string")
                .ccpa(Ccpa.of("1YYY"))
                .coppa(1)
                .build();
        given(privacyExtractor.validPrivacyFrom(any(), any())).willReturn(privacy);

        given(ipAddressHelper.maskIpv4(anyString())).willReturn("maskedIpV4");
        given(ipAddressHelper.anonymizeIpv6(anyString())).willReturn("maskedIpV6");

        given(tcfDefinerService.resolveTcfContext(any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(Future.succeededFuture(TcfContext.empty()));

        final BidRequest bidRequest = givenBidRequest(request -> request.device(Device.builder().ipv6("ipV6").build()));
        final AuctionContext auctionContext = givenAuctionContext(context -> context
                .httpRequest(givenHttpRequestContext("invalid"))
                .bidRequest(bidRequest));

        // when
        target.contextFrom(auctionContext);

        // then
        verify(ipAddressHelper).anonymizeIpv6(anyString());
    }

    @Test
    public void contextFromShouldAddRefUrlWhenPresentAndRequestTypeIsWeb() {
        // given
        final Privacy privacy = Privacy.builder()
                .gdpr("1")
                .consentString("consent_string")
                .ccpa(Ccpa.EMPTY)
                .coppa(0)
                .build();
        given(privacyExtractor.validPrivacyFrom(any(), any())).willReturn(privacy);

        given(tcfDefinerService.resolveTcfContext(any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(Future.succeededFuture(TcfContext.empty()));

        final BidRequest bidRequest = givenBidRequest(request -> request.site(Site.builder().ref("refUrl").build()));
        final AuctionContext auctionContext = givenAuctionContext(context -> context
                .requestTypeMetric(MetricName.openrtb2web)
                .httpRequest(givenHttpRequestContext("invalid"))
                .bidRequest(bidRequest));

        // when
        target.contextFrom(auctionContext);

        // then
        final RequestLogInfo expectedRequestLogInfo = RequestLogInfo.of(MetricName.openrtb2web, "refUrl", null);
        verify(tcfDefinerService)
                .resolveTcfContext(any(), any(), any(), any(), any(), eq(expectedRequestLogInfo), any(), any());
    }

    private static AuctionContext givenAuctionContext(
            UnaryOperator<AuctionContext.AuctionContextBuilder> auctionContextCustomizer) {

        final AuctionContext.AuctionContextBuilder defaultAuctionContextBuilder =
                AuctionContext.builder()
                        .httpRequest(givenHttpRequestContext(null))
                        .debugWarnings(new ArrayList<>())
                        .account(Account.builder().build())
                        .prebidErrors(new ArrayList<>())
                        .bidRequest(givenBidRequest(identity()))
                        .timeoutContext(TimeoutContext.of(0L, null, 0));

        return auctionContextCustomizer.apply(defaultAuctionContextBuilder).build();
    }

    private static BidRequest givenBidRequest(UnaryOperator<BidRequest.BidRequestBuilder> bidRequestCustomizer) {
        return bidRequestCustomizer.apply(BidRequest.builder()).build();
    }

    private static HttpRequestContext givenHttpRequestContext(String consentType) {
        final CaseInsensitiveMultiMap.Builder queryParamBuilder =
                CaseInsensitiveMultiMap.builder();

        if (StringUtils.isNotEmpty(consentType)) {
            queryParamBuilder.add("consent_type", consentType);
        }

        return HttpRequestContext.builder()
                .queryParams(queryParamBuilder.build())
                .build();
    }
}
