package org.prebid.server.bidder.inmobi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.iab.openrtb.request.Banner;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Format;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.response.Bid;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.SeatBid;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.prebid.server.bidder.Bidder;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.bidder.model.BidderCall;
import org.prebid.server.bidder.model.BidderError;
import org.prebid.server.bidder.model.HttpRequest;
import org.prebid.server.bidder.model.Result;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.json.DecodeException;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.proto.openrtb.ext.ExtPrebid;
import org.prebid.server.proto.openrtb.ext.request.inmobi.ExtImpInmobi;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.util.BidderUtil;
import org.prebid.server.util.HttpUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class InmobiBidder implements Bidder<BidRequest> {

    private static final TypeReference<ExtPrebid<?, ExtImpInmobi>> INMOBI_EXT_TYPE_REFERENCE =
            new TypeReference<>() {
            };
    private static final int FIRST_IMP_INDEX = 0;

    private final String endpointUrl;
    private final JacksonMapper mapper;

    public InmobiBidder(String endpointUrl, JacksonMapper mapper) {
        this.endpointUrl = HttpUtil.validateUrl(Objects.requireNonNull(endpointUrl));
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public Result<List<HttpRequest<BidRequest>>> makeHttpRequests(BidRequest request) {
        final List<BidderError> errors = new ArrayList<>();

        final Imp imp = request.getImp().getFirst();
        final ExtImpInmobi extImpInmobi;

        try {
            extImpInmobi = parseImpExt(imp);
        } catch (PreBidException e) {
            return Result.withError(BidderError.badInput("bad InMobi bidder ext"));
        }

        if (StringUtils.isBlank(extImpInmobi.getPlc())) {
            return Result.withError(BidderError.badInput("'plc' is a required attribute for InMobi's bidder ext"));
        }

        final List<Imp> updatedImps = new ArrayList<>(request.getImp());
        updatedImps.set(FIRST_IMP_INDEX, updateImp(imp));

        final BidRequest outgoingRequest = request.toBuilder().imp(updatedImps).build();

        return Result.of(Collections.singletonList(BidderUtil.defaultRequest(outgoingRequest, endpointUrl, mapper)),
                errors);
    }

    private ExtImpInmobi parseImpExt(Imp imp) {
        try {
            return mapper.mapper().convertValue(imp.getExt(), INMOBI_EXT_TYPE_REFERENCE).getBidder();
        } catch (IllegalArgumentException e) {
            throw new PreBidException(e.getMessage(), e);
        }
    }

    private Imp updateImp(Imp imp) {
        final Banner banner = imp.getBanner();
        if (banner != null) {
            if ((banner.getW() == null || banner.getH() == null || banner.getW() == 0 || banner.getH() == 0)
                    && CollectionUtils.isNotEmpty(banner.getFormat())) {
                final Format format = banner.getFormat().getFirst();
                return imp.toBuilder().banner(banner.toBuilder().w(format.getW()).h(format.getH()).build()).build();
            }
        }
        return imp;
    }

    @Override
    public final Result<List<BidderBid>> makeBids(BidderCall<BidRequest> httpCall, BidRequest bidRequest) {
        try {
            final BidResponse bidResponse = mapper.decodeValue(httpCall.getResponse().getBody(), BidResponse.class);
            return Result.of(extractBids(bidResponse), Collections.emptyList());
        } catch (DecodeException | PreBidException e) {
            return Result.withError(BidderError.badServerResponse(e.getMessage()));
        }
    }

    private List<BidderBid> extractBids(BidResponse bidResponse) {
        if (bidResponse == null || CollectionUtils.isEmpty(bidResponse.getSeatbid())) {
            return Collections.emptyList();
        }
        return bidsFromResponse(bidResponse);
    }

    private List<BidderBid> bidsFromResponse(BidResponse bidResponse) {
        return bidResponse.getSeatbid().stream()
                .filter(Objects::nonNull)
                .map(SeatBid::getBid)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .map(bid -> BidderBid.of(bid, getBidType(bid), bidResponse.getCur()))
                .toList();
    }

    private static BidType getBidType(Bid bid) {
        final Integer mtype = bid.getMtype();
        return switch (mtype) {
            case 1 -> BidType.banner;
            case 2 -> BidType.video;
            case 4 -> BidType.xNative;
            case null, default -> throw new PreBidException("Unsupported mtype %d for bid %s"
                    .formatted(mtype, bid.getId()));
        };
    }
}
