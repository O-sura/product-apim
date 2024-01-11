package org.wso2.am.integration.tests.other;

import com.google.gson.Gson;
import org.json.JSONException;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.wso2.am.integration.clients.publisher.api.ApiException;
import org.wso2.am.integration.clients.publisher.api.v1.dto.APIDTO;
import org.wso2.am.integration.clients.publisher.api.v1.dto.APIOperationPoliciesDTO;
import org.wso2.am.integration.clients.publisher.api.v1.dto.OperationPolicyDTO;
import org.wso2.am.integration.clients.store.api.v1.dto.ApplicationDTO;
import org.wso2.am.integration.clients.store.api.v1.dto.ApplicationKeyDTO;
import org.wso2.am.integration.clients.store.api.v1.dto.ApplicationKeyGenerateRequestDTO;
import org.wso2.am.integration.test.utils.APIManagerIntegrationTestException;
import org.wso2.am.integration.test.utils.base.APIMIntegrationConstants;
import org.wso2.am.integration.test.utils.bean.APIRequest;
import org.wso2.am.integration.tests.api.lifecycle.APIManagerLifecycleBaseTest;
import org.wso2.carbon.automation.test.utils.http.client.HttpRequestUtil;
import org.wso2.carbon.automation.test.utils.http.client.HttpResponse;
import org.wso2.carbon.integration.common.utils.FileManager;
import org.wso2.carbon.integration.common.utils.exceptions.AutomationUtilException;
import org.wso2.carbon.integration.common.utils.mgt.ServerConfigurationManager;
import org.wso2.carbon.utils.ServerConstants;

import javax.xml.xpath.XPathExpressionException;
import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertTrue;

public class ELKAnalyticsWithRespondMediatorTestCase extends APIManagerLifecycleBaseTest {
    private final String ELK_API_NAME = "ElkAnalyticsAPI";
    private final String API_VERSION_1_0_0 = "1.0.0";
    private final String INVOKABLE_API_CONTEXT = "elkapi";
    private final String API_END_POINT_POSTFIX_URL = "jaxrs_basic/services/customers/customerservice/";
    private final String API_GET_ENDPOINT_METHOD = "/customers/123";
    private final String POLICY_NAME = "respondMediatorPolicy";
    private final String ELK_APPLICATION_NAME = "ElkAnalyticsApplication";
    private ServerConfigurationManager serverConfigurationManager;
    private String applicationId;
    private String apiId;
    private String accessToken;

    @BeforeClass(alwaysRun = true)
    public void initialize() throws JSONException, APIManagerIntegrationTestException, ApiException,
            IOException, AutomationUtilException, XPathExpressionException,
            org.wso2.am.integration.clients.store.api.ApiException {
        super.init();

        serverConfigurationManager = new ServerConfigurationManager(superTenantKeyManagerContext);

        serverConfigurationManager.applyConfiguration(new File(getAMResourceLocation()
                + File.separator + "configFiles" + File.separator + "ElkAnalytics" +
                File.separator + "deployment.toml"));

        HttpResponse applicationResponse = restAPIStore.createApplication(ELK_APPLICATION_NAME,
                "Test Application for ELK", APIMIntegrationConstants.APPLICATION_TIER.UNLIMITED,
                ApplicationDTO.TokenTypeEnum.JWT);
        applicationId = applicationResponse.getData();

        String apiEndPointUrl = backEndServerUrl.getWebAppURLHttp() + API_END_POINT_POSTFIX_URL;
        APIRequest apiRequest = new APIRequest(ELK_API_NAME, INVOKABLE_API_CONTEXT, new URL(apiEndPointUrl));
        apiRequest.setVersion(API_VERSION_1_0_0);
        apiRequest.setTiersCollection(APIMIntegrationConstants.API_TIER.UNLIMITED);
        apiRequest.setTier(APIMIntegrationConstants.API_TIER.UNLIMITED);
        apiRequest.setTags(API_TAGS);
        apiId = createPublishAndSubscribeToAPIUsingRest(apiRequest, restAPIPublisher, restAPIStore, applicationId,
                APIMIntegrationConstants.API_TIER.UNLIMITED);

        ArrayList grantTypes = new ArrayList();
        grantTypes.add("client_credentials");

        ApplicationKeyDTO applicationKeyDTO = restAPIStore.generateKeys(applicationId, "3600", null,
                ApplicationKeyGenerateRequestDTO.KeyTypeEnum.PRODUCTION, null, grantTypes);
        accessToken = applicationKeyDTO.getToken().getAccessToken();
    }

    @Test(groups = {"wso2.am"}, description = "Test ELK Analytics with Respond Mediator")
    public void testELKAnalyticsWithRespondMediator() throws Exception {
        // Add metrics logger to log4j2.properties
        String log4jPropertiesFile = getAMResourceLocation() + File.separator + "configFiles"
                + File.separator + "ElkAnalytics" + File.separator + "log4j2.properties";
        String log4jPropertiesTargetLocation = System.getProperty(ServerConstants.CARBON_HOME) + File.separator
                + "repository" + File.separator + "conf" + File.separator + "log4j2.properties";
        FileManager.copyFile(new File(log4jPropertiesFile), log4jPropertiesTargetLocation);

        // Add common operation policy with respond mediator
        addNewOperationPolicy();
        Map<String, String> updatedCommonPolicyMap = restAPIPublisher.getAllCommonOperationPolicies();
        Assert.assertNotNull(updatedCommonPolicyMap.get("respondMediatorPolicy"),
                "Unable to find the newly added common policy");

        // Add policy to API
        HttpResponse getAPIResponse = restAPIPublisher.getAPI(apiId);
        APIDTO apidto = new Gson().fromJson(getAPIResponse.getData(), APIDTO.class);
        APIOperationPoliciesDTO apiOperationPoliciesDTO = new APIOperationPoliciesDTO();
        List<OperationPolicyDTO> policyList = new ArrayList<>();
        OperationPolicyDTO policyDTO = new OperationPolicyDTO();
        policyDTO.setPolicyName(POLICY_NAME);
        policyDTO.setPolicyId(updatedCommonPolicyMap.get(POLICY_NAME));
        policyList.add(policyDTO);
        apiOperationPoliciesDTO.setRequest(policyList);

        apidto.getOperations().get(0).setOperationPolicies(apiOperationPoliciesDTO);
        restAPIPublisher.updateAPI(apidto);
        // Create Revision and Deploy to Gateway
        createAPIRevisionAndDeployUsingRest(apiId, restAPIPublisher);
        waitForAPIDeployment();


        Map<String, String> requestHeaders = new HashMap<String, String>();
        requestHeaders.put("Authorization", "Bearer " + accessToken);

        HttpResponse response = HttpRequestUtil
                .doGet(getAPIInvocationURLHttp(INVOKABLE_API_CONTEXT, API_VERSION_1_0_0) +
                        API_GET_ENDPOINT_METHOD, requestHeaders);

        String metricsFile = System.getProperty(ServerConstants.CARBON_HOME) + File.separator + "repository"
                + File.separator + "logs" + File.separator + "apim_metrics.log";
        File file = new File(metricsFile);
        assertTrue(file.exists(), "Metrics file not found in " + metricsFile);
        assertTrue(readFileContent(metricsFile), "Metrics file does not contain the expected content");

    }

    public void addNewOperationPolicy() throws ApiException {
        String policySpecPath = getAMResourceLocation() + File.separator + "configFiles"
                + File.separator + "ElkAnalytics" + File.separator + "respondMediatorPolicy.json";

        String synapsePolicyDefPath = getAMResourceLocation() + File.separator + "configFiles"
                + File.separator + "ElkAnalytics" + File.separator + "respondMediatorPolicy.j2";

        File specification = new File(policySpecPath);
        File synapseDefinition = new File(synapsePolicyDefPath);

        HttpResponse addPolicyResponse = restAPIPublisher.addCommonOperationPolicy(specification, synapseDefinition,
                null);
    }

    public boolean readFileContent(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains(ELK_API_NAME) && line.contains("\"destination\":\"dummy_endpoint_address\"")) {
                    return true;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return false;
    }

    @AfterClass(alwaysRun = true)
    public void destroy() throws Exception {

        restAPIStore.deleteApplication(applicationId);
        undeployAndDeleteAPIRevisionsUsingRest(apiId, restAPIPublisher);
        restAPIPublisher.deleteAPI(apiId);
    }
}
