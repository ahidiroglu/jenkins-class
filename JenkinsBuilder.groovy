// Uniq name for the pod or slave 
def k8slabel = "jenkins-pipeline-${UUID.randomUUID().toString()}"
def branch = "${scm.branches[0].name}".replaceAll(/^\*\//, '')
def gitCommitHash = ''
def environment = ""

if (branch == "master") {
  println("The application will be deployed to stage environment!")
  environment = "stage"
} else if (branch.contains('dev-feature')) {
  println("The application will be deployed to dev environment!")
  environment = "dev"
} else if (branch.contains('qa-feature')) {
  println("The application will be deployed to qa environment!")
  environment = "qa"
} else {
  println('Please use proper name for your branch!')
  currentBuild.result = 'FAILURE'
  println("ERROR Detected:")
}

// yaml def for slaves 
def slavePodTemplate = """
      metadata:
        labels:
          k8s-label: ${k8slabel}
        annotations:
          jenkinsjoblabel: ${env.JOB_NAME}-${env.BUILD_NUMBER}
      spec:
        affinity:
          podAntiAffinity:
            requiredDuringSchedulingIgnoredDuringExecution:
            - labelSelector:
                matchExpressions:
                - key: component
                  operator: In
                  values:
                  - jenkins-jenkins-master
              topologyKey: "kubernetes.io/hostname"
        containers:
        - name: docker
          image: docker:latest
          imagePullPolicy: IfNotPresent
          command:
          - cat
          tty: true
          volumeMounts:
            - mountPath: /var/run/docker.sock
              name: docker-sock
        serviceAccountName: default
        securityContext:
          runAsUser: 0
          fsGroup: 0
        volumes:
          - name: docker-sock
            hostPath:
              path: /var/run/docker.sock
    """
    podTemplate(name: k8slabel, label: k8slabel, yaml: slavePodTemplate, showRawYaml: false) {
      node(k8slabel) {

        stage("Checkout SCM") {
            checkout scm
            gitCommitHash = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
        }
        dir ("deployments/docker"){
            container('docker') {
                withCredentials([usernamePassword(credentialsId: 'docker-creds', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                    stage('Docker build'){
                        sh 'docker build -t artemis .'
                    }
                    stage('Docker Login') {
                        sh "docker login --username $USERNAME  --password $PASSWORD"
                    }
                    stage('Docker Push') {
                        if (params.pushLatest) {
                            println ('Pushing the image to latest version!!!')
                            sh "docker tag artemis ahidiroglu/artemis:latest"
                            sh "docker push ahidiroglu/artemis:latest"
                        }
                        sh "docker tag artemis ahidiroglu/artemis:${gitCommitHash}"
                        sh "docker push ahidiroglu/artemis:${gitCommitHash}"
                    }        
                    stage("Trigger Deploy") {
                      build job: 'artemis-deploy', 
                      parameters: [
                        booleanParam(name: 'applyChanges', value: true), 
                        booleanParam(name: 'destroyChanges', value: false), 
                        string(name: 'selectedDockerimage', value: "ahidiroglu/artemis:${gitCommitHash"), 
                        string(name: 'environment ', value: "${environment}")
                        ]
                    }
            }
        } 
      }
    }
    }
       



        