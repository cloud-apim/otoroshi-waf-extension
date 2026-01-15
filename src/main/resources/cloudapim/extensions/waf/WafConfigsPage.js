
class WafConfigsPage extends Component {

  formSchema = {
    _loc: {
      type: 'location',
      props: {},
    },
    id: {
      type: 'string',
      disabled: true,
      props: { label: 'Id', placeholder: '---' }
    },
    name: {
      type: 'string',
      props: { label: 'Name', placeholder: 'My WAF Configuration' },
    },
    description: {
      type: 'string',
      props: { label: 'Description', placeholder: 'Description of the WAF configuration' },
    },
    metadata: {
      type: 'object',
      props: { label: 'Metadata' },
    },
    tags: {
      type: 'array',
      props: { label: 'Tags' },
    },
    enabled: {
      type: 'bool',
      props: { label: 'Enabled' },
    },
    block: {
      type: 'bool',
      props: { label: 'Block requests', help: 'Block requests when a rule matches' },
    },
    inspect_input_body: {
      type: 'bool',
      props: { label: 'Inspect input body', help: 'Inspect the request body' },
    },
    inspect_output_body: {
      type: 'bool',
      props: { label: 'Inspect output body', help: 'Inspect the response body' },
    },
    input_body_limit: {
      type: 'number',
      props: { label: 'Input body limit', placeholder: '1024', suffix: 'bytes', help: 'Maximum size of the request body to inspect' },
    },
    output_body_limit: {
      type: 'number',
      props: { label: 'Output body limit', placeholder: '1024', suffix: 'bytes', help: 'Maximum size of the response body to inspect' },
    },
    output_body_mimetypes: {
      type: 'array',
      props: { label: 'Output body MIME types', placeholder: 'text/plain', help: 'MIME types to inspect in the response body' },
    },
    rules: {
      type: 'array',
      props: { label: 'Rules', placeholder: 'WAF rules to apply' },
    },
  };

  columns = [
    {
      title: 'Name',
      filterId: 'name',
      content: (item) => item.name,
    },
    {
      title: 'Enabled',
      filterId: 'enabled',
      content: (item) => item.enabled ? 'Yes' : 'No',
      style: { textAlign: 'center', width: 80 },
    },
    {
      title: 'Block',
      filterId: 'block',
      content: (item) => item.block ? 'Yes' : 'No',
      style: { textAlign: 'center', width: 80 },
    },
  ];

  formFlow = [
    '_loc',
    'id',
    'name',
    'description',
    '>>>Metadata and tags',
    'tags',
    'metadata',
    '<<<Configuration',
    'enabled',
    'block',
    '>>>Request inspection',
    'inspect_input_body',
    'input_body_limit',
    '>>>Response inspection',
    'inspect_output_body',
    'output_body_limit',
    'output_body_mimetypes',
    '<<<Rules',
    'rules',
  ];

  componentDidMount() {
    this.props.setTitle(`WAF Configurations`);
  }

  client = BackOfficeServices.apisClient('waf.extensions.cloud-apim.com', 'v1', 'waf-configs');

  render() {
    return (
      React.createElement(Table, {
        parentProps: this.props,
        selfUrl: "extensions/cloud-apim/waf/wafconfigs",
        defaultTitle: "All WAF configurations",
        defaultValue: () => {
          return {
            id: 'waf-config_' + uuid(),
            name: 'New WAF Configuration',
            description: 'A new WAF configuration',
            tags: [],
            metadata: {},
            enabled: true,
            block: true,
            inspect_input_body: true,
            inspect_output_body: true,
            input_body_limit: null,
            output_body_limit: null,
            output_body_mimetypes: [],
            rules: [
              "@import_preset crs",
              "SecRuleEngine On",
            ],
          }
        },
        itemName: "WAF Configuration",
        formSchema: this.formSchema,
        formFlow: this.formFlow,
        columns: this.columns,
        stayAfterSave: true,
        fetchItems: (paginationState) => this.client.findAll(),
        updateItem: this.client.update,
        deleteItem: this.client.delete,
        createItem: this.client.create,
        navigateTo: (item) => {
          window.location = `/bo/dashboard/extensions/cloud-apim/waf/wafconfigs/edit/${item.id}`
        },
        itemUrl: (item) => `/bo/dashboard/extensions/cloud-apim/waf/wafconfigs/edit/${item.id}`,
        showActions: true,
        showLink: true,
        rowNavigation: true,
        extractKey: (item) => item.id,
        export: true,
        kubernetesKind: "waf.extensions.cloud-apim.com/WafConfig",
      }, null)
    );
  }
}
