package co.imaginedata.samplecontroller;

import co.imaginedata.stable.models.V1Database;
import co.imaginedata.stable.models.V1DatabaseList;
import io.kubernetes.client.extended.controller.Controller;
import io.kubernetes.client.extended.controller.ControllerManager;
import io.kubernetes.client.extended.controller.LeaderElectingController;
import io.kubernetes.client.extended.controller.builder.ControllerBuilder;
import io.kubernetes.client.extended.controller.reconciler.Reconciler;
import io.kubernetes.client.extended.controller.reconciler.Request;
import io.kubernetes.client.extended.controller.reconciler.Result;
import io.kubernetes.client.extended.leaderelection.LeaderElectionConfig;
import io.kubernetes.client.extended.leaderelection.LeaderElector;
import io.kubernetes.client.extended.leaderelection.resourcelock.EndpointsLock;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import io.kubernetes.client.util.generic.GenericKubernetesApi;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws IOException {
        // reading from .kube/config allows to connect to e.g. the minikube cluster
        String kubeConfigPath = System.getenv("HOME") + "/.kube/config";

        // loading the out-of-cluster config, a kubeconfig from file-system
        final KubeConfig kubeConfig = KubeConfig.loadKubeConfig(new FileReader(kubeConfigPath));

        logger.info("Using configuration for context: " + kubeConfig.getCurrentContext());

        ApiClient apiClient =
                ClientBuilder.kubeconfig(kubeConfig).build();
        Configuration.setDefaultApiClient(apiClient);

        OkHttpClient httpClient =
                apiClient.getHttpClient()
                        .newBuilder()
                        .readTimeout(0, TimeUnit.SECONDS)
                        .build();
        apiClient.setHttpClient(httpClient);

        GenericKubernetesApi<V1Database, V1DatabaseList> databaseApi = new GenericKubernetesApi<>(
                V1Database.class,
                V1DatabaseList.class,
                "stable.imaginedata.co",
                "v1",
                "databases",
                apiClient);

        // instantiating an informer-factory, and there should be only one informer-factory
        // globally.
        SharedInformerFactory informerFactory = new SharedInformerFactory();
        // registering database-informer into the informer-factory.
        SharedIndexInformer<V1Database> databaseInformer =
                informerFactory.sharedIndexInformerFor(
                        databaseApi,
                        V1Database.class,
                        10000l
                );
        informerFactory.startAllRegisteredInformers();

        // databasePrintingReconciler prints node information on events
        DatabasePrintingReconciler databasePrintingReconciler = new DatabasePrintingReconciler(databaseInformer, "default");

        // Use builder library to construct a default controller.
        Controller controller =
                ControllerBuilder.defaultBuilder(informerFactory)
                        .watch(
                                (workQueue) -> ControllerBuilder.controllerWatchBuilder(V1Database.class, workQueue).build()
                        )
                        .withReconciler(databasePrintingReconciler) // required, set the actual reconciler
                        .withName("db-printing-controller") // optional, set name for controller
                        .withWorkerCount(4) // optional, set worker thread count
                        .withReadyFunc(databaseInformer::hasSynced) // optional, only starts controller when the cache has synced up
                        .build();

        // Use builder library to manage one or multiple controllers.
        ControllerManager controllerManager =
                ControllerBuilder.controllerManagerBuilder(informerFactory)
                        .addController(controller)
                        .build();

        LeaderElectingController leaderElectingController =
                new LeaderElectingController(
                        new LeaderElector(
                                new LeaderElectionConfig(
                                        new EndpointsLock("kube-system", "leader-election", "foo"),
                                        Duration.ofMillis(10000),
                                        Duration.ofMillis(8000),
                                        Duration.ofMillis(5000)
                                )
                        ),
                        controllerManager);

        leaderElectingController.run();
    }

    static class DatabasePrintingReconciler implements Reconciler {
        private Lister<V1Database> dbLister;

        public DatabasePrintingReconciler(SharedIndexInformer<V1Database> dbInformer, String namespace) {
            this.dbLister = new Lister<>(dbInformer.getIndexer(), namespace);
        }

        @Override
        public Result reconcile(Request request) {
            V1Database database = this.dbLister.get(request.getName());

            logger.info("V1Database: " + database);
            return new Result(false);
        }
    }
}

