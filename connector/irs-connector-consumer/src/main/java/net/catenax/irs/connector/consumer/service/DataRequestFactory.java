//
// Copyright (c) 2021 Copyright Holder (Catena-X Consortium)
//
// See the AUTHORS file(s) distributed with this work for additional
// information regarding authorship.
//
// See the LICENSE file(s) distributed with this work for
// additional information regarding license terms.
//
package net.catenax.irs.connector.consumer.service;


import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import net.catenax.irs.component.ChildItem;
import net.catenax.irs.component.Relationship;
import net.catenax.irs.connector.constants.IrsConnectorConstants;
import net.catenax.irs.connector.consumer.configuration.ConsumerConfiguration;
import net.catenax.irs.connector.consumer.registry.StubRegistryClient;
import net.catenax.irs.connector.requests.JobsTreeRequest;
import net.catenax.irs.connector.util.JsonUtil;
import org.eclipse.dataspaceconnector.schema.azure.AzureBlobStoreSchema;
import org.eclipse.dataspaceconnector.spi.EdcException;
import org.eclipse.dataspaceconnector.spi.monitor.Monitor;
import org.eclipse.dataspaceconnector.spi.types.domain.metadata.DataEntry;
import org.eclipse.dataspaceconnector.spi.types.domain.transfer.DataAddress;
import org.eclipse.dataspaceconnector.spi.types.domain.transfer.DataRequest;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;

import static java.lang.String.format;

/**
 * Generates EDC {@link DataRequest}s populated for calling Providers to invoke the IRS API
 * to retrieve partial parts trees.
 */
@RequiredArgsConstructor
@SuppressWarnings("PMD.GuardLogStatement") // Monitor doesn't offer guard statements
public class DataRequestFactory {

    /**
     * The name of the blob to be created in each Provider call.
     * The suffix ".complete" is required in order to signal to the
     * EDC ObjectContainerStatusChecker that a transfer is complete.
     * The checker lists blobs on the destination container until a blob with this suffix
     * in the name is present.
     */
    /* package */ static final String PARTIAL_PARTS_TREE_BLOB_NAME = "partialPartsTree.complete";
    /**
     * Logger.
     */
    private final Monitor monitor;
    /**
     * Storage account name.
     */
    private final ConsumerConfiguration configuration;
    /**
     * Json Converter.
     */
    private final JsonUtil jsonUtil;
    /**
     * Registry client to resolve Provider URL by Part ID.
     */
    private final StubRegistryClient registryClient;

    /**
     * Generates EDC {@link DataRequest}s populated for calling Providers to invoke the IRS API
     * to retrieve partial parts trees.
     * <p>
     * If the {@code previousUrlOrNull} argument is non-{@code null}, this method will not return
     * data requests pointing to that Provider URL. This ensures only parts tree queries pointing
     * to other providers are issued in subsequent recursive retrievals.
     *
     * @param requestContext current IRS request data.
     * @param childItem        the child items for which to retrieve partial jobs trees.
     * @return a {@link DataRequest} for each item {@code partIds} for which the Provider URL
     * was resolves in the registry <b>and</b> is not identical to {@code previousUrlOrNull},
     * that allows retrieving the partial parts tree for the given part.
     */
    /* package */ Stream<DataRequest> createRequests(
            final RequestContext requestContext,
            final ChildItem childItem) {
        return createRequest(requestContext, childItem);
    }

    private Stream<DataRequest> createRequest(
            final RequestContext requestContext,
            final ChildItem childItem) {

        // Resolve Provider URL for part from registry
        final var registryResponse = registryClient.getUrl(childItem);
        if (registryResponse.isEmpty()) {
            monitor.info(format("Registry did not resolve %s", childItem));
            return Stream.empty();
        }

        final var providerUrlForPartId = registryResponse.get();

        // If provider URL has not changed between requests, then children
        // for that part have already been fetched in the previous request.
        if (Objects.equals(requestContext.previousUrlOrNull, providerUrlForPartId)) {
            monitor.debug(format("Not issuing a new request for %s, URL unchanged", childItem));
            return Stream.empty();
        }

        int remainingDepth = requestContext.depth;
        if (requestContext.previousUrlOrNull != null) {
            final var usedDepth = Dijkstra.shortestPathLength(requestContext.getRelationships(), requestContext.getRelationship(), requestContext.getRelationship())
                    .orElseThrow(() -> new EdcException("Unconnected child items returned by IRS"));
            remainingDepth -= usedDepth;
            if (remainingDepth <= 0) {
                monitor.debug(format("Not issuing a new request for %s, depth exhausted", childItem));
                return Stream.empty();
            }
        }

        final var newIrsRequest = requestContext.requestTemplate.getByObjectIdRequest().toBuilder()
                .childCatenaXId(childItem.getChildCatenaXId())
                .depth(remainingDepth)
                .build();

        final var irsRequestAsString = jsonUtil.asString(newIrsRequest);

        monitor.info(format("Mapped data request to url: %s, previous depth: %d, new depth: %d",
                providerUrlForPartId,
                requestContext.depth,
                remainingDepth));

        return Stream.of(DataRequest.Builder.newInstance()
                .id(UUID.randomUUID().toString()) //this is not relevant, thus can be random
                .connectorAddress(providerUrlForPartId) //the address of the provider connector
                .protocol("ids-rest") //must be ids-rest
                .connectorId("consumer")
                .dataEntry(DataEntry.Builder.newInstance() //the data entry is the source asset
                        .id(IrsConnectorConstants.IRS_REQUEST_ASSET_ID)
                        .policyId(IrsConnectorConstants.IRS_REQUEST_POLICY_ID)
                        .build())
                .dataDestination(DataAddress.Builder.newInstance()
                        .type(AzureBlobStoreSchema.TYPE) //the provider uses this to select the correct DataFlowController
                        .property(AzureBlobStoreSchema.ACCOUNT_NAME, configuration.getStorageAccountName())
                        .build())
                .properties(Map.of(
                        IrsConnectorConstants.DATA_REQUEST_IRS_REQUEST_PARAMETERS, irsRequestAsString,
                        IrsConnectorConstants.DATA_REQUEST_IRS_DESTINATION_PATH, PARTIAL_PARTS_TREE_BLOB_NAME
                ))
                .managedResources(true)
                .build());
    }

    /**
     * Parameter Object used to pass information about the previous IRS request
     * and its results, to the
     * method for creatin subsequent IRS requests.
     */
    @Value
    @Builder
    /* package */ static class RequestContext {
        /**
         * The original IRS request received from the client.
         */
        private JobsTreeRequest requestTemplate;

        private Collection<Relationship> relationships;

        private ChildItem relationship;
        /**
         * the Provider URL used for retrieving the {@code partIds}, or {@code null} for the first retrieval.
         */
        private String previousUrlOrNull;
        /**
         * The queried partId in the {@link #requestTemplate}.
         */
        private String childCatenaXId;

        /**
         * The queried partId in the {@link #requestTemplate}.
         */
        private String lifecycleContext;

        private LocalDateTime assembledOn;

        private LocalDateTime lastModifiedOn;
        /**
         * The query depth used in the current query.
         */
        private int depth;
    }
}
