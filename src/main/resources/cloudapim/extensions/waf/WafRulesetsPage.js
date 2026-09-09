class WafRulesetsPage extends Component {

  formSchema = {
    _loc: { type: 'location', props: {} },
    id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
    name: { type: 'string', props: { label: 'Name', placeholder: 'Baseline CRS' } },
    description: { type: 'string', props: { label: 'Description', placeholder: 'The rules every public route shares' } },
    tags: { type: 'array', props: { label: 'Tags' } },
    metadata: { type: 'object', props: { label: 'Metadata' } },
    enabled: {
      type: 'bool',
      props: { label: 'Enabled', help: 'A disabled ruleset contributes nothing to the configs that reference it' },
    },
    rules: { type: 'array', props: { component: MonacoRule } },
    compile: { type: CompileButton, props: {} },
  };

  columns = [
    { title: 'Name', filterId: 'name', content: (item) => item.name },
    {
      title: 'Enabled',
      filterId: 'enabled',
      content: (item) => (item.enabled ? 'Yes' : 'No'),
      style: { textAlign: 'center', width: 80 },
    },
    {
      title: 'Rules',
      content: (item) => String((item.rules || []).length),
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
    '<<<Rules',
    'rules',
    'compile',
  ];

  componentDidMount() {
    this.props.setTitle(`WAF Rulesets`);
  }

  client = BackOfficeServices.apisClient('waf.extensions.cloud-apim.com', 'v1', 'waf-rulesets');

  render() {
    return (
      React.createElement(Table, {
        parentProps: this.props,
        selfUrl: "extensions/cloud-apim/waf/wafrulesets",
        defaultTitle: "All WAF rulesets",
        defaultValue: () => {
          return {
            id: 'waf-ruleset_' + uuid(),
            name: 'New ruleset',
            description: 'A reusable body of SecLang rules',
            tags: [],
            metadata: {},
            enabled: true,
            rules: [
              "@import_preset crs",
            ],
          }
        },
        itemName: "WAF Ruleset",
        formSchema: this.formSchema,
        formFlow: this.formFlow,
        columns: this.columns,
        stayAfterSave: true,
        fetchItems: (paginationState) => this.client.findAll(),
        updateItem: this.client.update,
        deleteItem: this.client.delete,
        createItem: this.client.create,
        navigateTo: (item) => {
          window.location = `/bo/dashboard/extensions/cloud-apim/waf/wafrulesets/edit/${item.id}`
        },
        itemUrl: (item) => `/bo/dashboard/extensions/cloud-apim/waf/wafrulesets/edit/${item.id}`,
        showActions: true,
        showLink: false,
        rowNavigation: true,
        extractKey: (item) => item.id,
        export: true,
        kubernetesKind: "waf.extensions.cloud-apim.com/WafRuleset"
      }, null)
    );
  }
}
